package com.aetria.roamersaddon;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.TickEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber(modid = RoamersAddonMod.MODID)
public final class RoamersAddonEvents {
    private static final Logger LOGGER = LoggerFactory.getLogger("RoamersAddon");

    // Persistent flags stored on the Roamer entity
    private static final String TAG_INIT_DONE = "roamers_addon_init_done";
    private static final String TAG_SPAWN_SAPLINGS_DONE = "roamers_addon_spawn_saplings_done";
    private static final String TAG_STARTKIT_DONE = "roamers_addon_startkit_done";

    private static final String ROOT = "roamers_addon";
    private static final String KEY_WANTED = "wanted_key";
    private static final String KEY_WANTED_SINCE = "wanted_since";
    private static final String KEY_PITY = "pity";
    private static final String KEY_START_CHEST = "start_chest_pos";
    private static final String KEY_NEXT_SAPLING_ATTEMPT = "next_sapling_attempt";

    private static final int INIT_DELAY_TICKS = 20;                   // 1 second after first seen
    private static final int SAPLING_RETRY_TICKS = 20 * 5;            // retry giving spawn saplings every 5s until land/buildings are ready
    private static final int AUTO_HELP_AFTER_TICKS = 20 * 60 * 5;     // 5 minutes
    private static final int AUTO_HELP_COOLDOWN_TICKS = 20 * 60 * 5;  // 5 minutes per same "wanted key"

    private static final int GIVE_BUILD_AMOUNT = 16;
    private static final int GIVE_CRAFT_AMOUNT = 8;

    private static final int CHEST_SEARCH_RADIUS = 12;
    private static final int TREE_SEARCH_RADIUS = 16;

    private final Set<Entity> trackedRoamers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> pendingInitAtGameTime = new ConcurrentHashMap<>();

    // Replant anti-spam (base position -> expireTick)
    private final Map<Long, Long> recentReplants = new ConcurrentHashMap<>();

    @SubscribeEvent
    public void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;

        Entity e = event.getEntity();
        if (!RoamersCompat.isRoamerEntity(e)) return;

        trackedRoamers.add(e);

        // Delay init so Roamers has time to finish setting Land/buildings
        long gameTime = event.getLevel().getGameTime();
        pendingInitAtGameTime.putIfAbsent(e.getUUID(), gameTime + INIT_DELAY_TICKS);
    }

    @SubscribeEvent
    public void onLevelTick(TickEvent.LevelTickEvent.Post event) {
        Level level = event.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) return;

        long gameTime = serverLevel.getGameTime();
        if ((gameTime % 20L) != 0L) return; // run once per second

        // clean recent replants occasionally
        if ((gameTime % 200L) == 0L) {
            long now = gameTime;
            recentReplants.entrySet().removeIf(en -> en.getValue() <= now);
        }

        List<Entity> snapshot = new ArrayList<>(trackedRoamers);
        for (Entity e : snapshot) {
            if (e == null) continue;
            if (!e.isAlive()) {
                trackedRoamers.remove(e);
                pendingInitAtGameTime.remove(e.getUUID());
                continue;
            }

            UUID id = e.getUUID();

            // Do one-time init when the delay expires
            Long initAt = pendingInitAtGameTime.get(id);
            if (initAt != null && gameTime >= initAt) {
                try {
                    if (!hasTag(e, TAG_INIT_DONE)) {
                        applySpawnSaplingsIfNeeded(serverLevel, e, gameTime);
                        setTag(e, TAG_INIT_DONE);
                    }
                } catch (Throwable t) {
                    LOGGER.error("Init failed for roamer {}", id, t);
                } finally {
                    pendingInitAtGameTime.remove(id);
                }
            }

            // 1) Keep trying to give initial saplings until Roamers has finished setting up Land/buildings
            try {
                applySpawnSaplingsIfNeeded(serverLevel, e, gameTime);
            } catch (Throwable t) {
                LOGGER.error("Failed spawn-sapling grant for roamer {}", id, t);
            }

            // 4 + 2) After Roamer places campfire + crafting table: place protected chest and plant saplings first
            try {
                tryPlaceStartKitIfReady(serverLevel, e, gameTime);
            } catch (Throwable t) {
                LOGGER.error("Failed start-kit placement for roamer {}", id, t);
            }

            // 4) While they're working: if they want something and it's in nearby chests, pull it into inventory
            // 2/5) If they want wood items and are stuck, ensure saplings exist + nudge mining/crafting.
            try {
                assistWithWantedItems(serverLevel, e, gameTime);
            } catch (Throwable t) {
                LOGGER.error("assistWithWantedItems failed for roamer {}", id, t);
            }
        }
    }

    @SubscribeEvent
    public void onInteract(PlayerInteractEvent.EntityInteractSpecific event) {
        if (event.getLevel().isClientSide()) return;

        Player player = event.getEntity();
        Entity target = event.getTarget();
        if (!RoamersCompat.isRoamerEntity(target)) return;

        ItemStack held = player.getItemInHand(event.getHand());
        if (!held.is(Items.STICK)) return;

        WantedInfo wanted = RoamersCompat.getWantedInfo(target);
        String activity = RoamersCompat.getActivityName(target);

        Component msg;
        if (wanted == null || wanted.item() == null) {
            msg = Component.literal("§aRoamer: §fI'm " + activity + " right now.");
        } else {
            String nice = RoamersCompat.niceItemName(wanted.item());
            switch (wanted.type) {
                case BUILD -> msg = Component.literal("§aRoamer: §fI'm " + activity + " — I need §e" + nice + "§f to keep building.");
                case CRAFT -> msg = Component.literal("§aRoamer: §fI'm " + activity + " — I'm trying to craft, but I'm missing §e" + nice + "§f.");
                default -> msg = Component.literal("§aRoamer: §fI'm " + activity + " — I'm looking for §e" + nice + "§f.");
            }
        }

        player.displayClientMessage(msg, false);
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public void onBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel().isClientSide()) return;

        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Entity breaker = event.getPlayer(); // may be null; roamers break via fake player sometimes; handle below

        // Chest protection: cancel if a roamer is breaking their own start chest
        // We handle this via RoamersCompat.shouldCancelChestBreak(...) which checks nearby roamers and their stored chest pos.
        try {
            if (RoamersCompat.shouldCancelChestBreak(level, event.getPos(), breaker, this::getStartChestPos)) {
                event.setCanceled(true);
                return;
            }
        } catch (Throwable t) {
            // swallow, don't break normal mining
        }

        // Replanting: if a roamer broke a log, replant a matching sapling if available
        try {
            Entity trueBreaker = RoamersCompat.getTrueBreakerEntity(level, event.getPos(), breaker);
            if (trueBreaker == null || !RoamersCompat.isRoamerEntity(trueBreaker)) return;

            BlockPos pos = event.getPos();
            BlockState state = event.getState();
            if (!RoamersCompat.isLogBlock(state)) return;

            long gameTime = level.getGameTime();
            BlockPos plantPos = findTreeBasePlantPos(level, pos);
            if (plantPos == null) return;

            long key = plantPos.asLong();
            Long until = recentReplants.get(key);
            if (until != null && until > gameTime) return;

            Object invObj = RoamersCompat.invoke(trueBreaker, "getInventory");
            if (!(invObj instanceof SimpleContainer inv)) return;

            Item saplingItem = RoamersCompat.saplingForLogBlock(state);
            if (saplingItem == null) return;

            if (!RoamersCompat.removeOne(inv, saplingItem)) return;

            // Delay by 1 tick so the broken block state is fully cleared
            level.getServer().execute(() -> {
                if (RoamersCompat.canPlantSaplingAt(level, plantPos)) {
                    RoamersCompat.placeSaplingItem(level, plantPos, saplingItem);
                    recentReplants.put(key, gameTime + 20L * 30L); // 30s anti-spam
                } else {
                    // put it back
                    RoamersCompat.giveToContainer(inv, new ItemStack(saplingItem, 1));
                }
            });
        } catch (Throwable t) {
            // ignore
        }
    }

    // ---------------------- State helpers ----------------------

    private static CompoundTag addonRoot(Entity e) {
        CompoundTag tag = e.getPersistentData();
        if (!tag.contains(ROOT)) tag.put(ROOT, new CompoundTag());
        return tag.getCompound(ROOT);
    }

    private static boolean hasTag(Entity e, String key) {
        return e.getPersistentData().getBoolean(key);
    }

    private static void setTag(Entity e, String key) {
        e.getPersistentData().putBoolean(key, true);
    }

    private static BlockPos getStartChestPos(Entity roamer) {
        CompoundTag root = addonRoot(roamer);
        if (!root.contains(KEY_START_CHEST)) return null;
        long packed = root.getLong(KEY_START_CHEST);
        return BlockPos.of(packed);
    }

    private static void setStartChestPos(Entity roamer, BlockPos pos) {
        addonRoot(roamer).putLong(KEY_START_CHEST, pos.asLong());
    }

    private void applySpawnSaplingsIfNeeded(ServerLevel level, Entity roamer, long gameTime) {
        if (hasTag(roamer, TAG_SPAWN_SAPLINGS_DONE)) return;

        // Roamers' Land/buildings can be initialized a bit after spawn. Keep retrying (cheaply) until ready.
        CompoundTag root = addonRoot(roamer);
        long nextTry = root.getLong(KEY_NEXT_SAPLING_ATTEMPT);
        if (nextTry != 0L && gameTime < nextTry) return;
        root.putLong(KEY_NEXT_SAPLING_ATTEMPT, gameTime + SAPLING_RETRY_TICKS);

        Set<Item> saplings = RoamersCompat.findSaplingsForRoamerStructures(roamer);
        if (saplings == null || saplings.isEmpty()) return;

        Object invObj = RoamersCompat.invoke(roamer, "getInventory");
        if (!(invObj instanceof SimpleContainer inv)) return;

        boolean gaveAny = false;
        for (Item sapling : saplings) {
            int have = RoamersCompat.count(inv, sapling);
            int want = 8;
            if (have >= want) continue;

            RoamersCompat.giveToContainer(inv, new ItemStack(sapling, want - have));
            gaveAny = true;
        }

        if (gaveAny) {
            setTag(roamer, TAG_SPAWN_SAPLINGS_DONE);
            root.remove(KEY_NEXT_SAPLING_ATTEMPT);
        }
    }

    /**
     * Start kit:
     * - place a chest in a "safe" spot (farther away so excavation doesn't hit it)
     * - plant saplings immediately around camp (so trees exist before building gets deep)
     */
    private void tryPlaceStartKitIfReady(ServerLevel level, Entity roamer, long gameTime) {
        if (hasTag(roamer, TAG_STARTKIT_DONE)) return;

        BlockPos campfirePos = RoamersCompat.findBuildStartPos(roamer, "CAMPFIRE");
        BlockPos craftingPos = RoamersCompat.findBuildStartPos(roamer, "CRAFTING_TABLE");
        if (campfirePos == null || craftingPos == null) return;

        if (!level.getBlockState(campfirePos).is(Blocks.CAMPFIRE)) return;
        if (!level.getBlockState(craftingPos).is(Blocks.CRAFTING_TABLE)) return;

        Object invObj = RoamersCompat.invoke(roamer, "getInventory");
        if (!(invObj instanceof SimpleContainer inv)) return;

        // 1) place chest safely
        BlockPos chestPos = findSafeChestPos(level, campfirePos, craftingPos);
        if (chestPos == null) return;

        placeChest(level, chestPos);
        setStartChestPos(roamer, chestPos);

        // Ensure they actually have the saplings we inferred before we try to plant them.
        applySpawnSaplingsIfNeeded(level, roamer, gameTime);

        // 2) plant saplings around camp BEFORE they get deep into building
        // Plant at least 1 of every sapling variant currently carried
        RoamersCompat.plantSaplingsInRing(level, campfirePos, inv, 1);

        // Also plant extra of the wood types used for structures (best effort)
        Set<Item> neededSaplings = RoamersCompat.findSaplingsForRoamerStructures(roamer);
        if (!neededSaplings.isEmpty()) {
            RoamersCompat.plantSpecificSaplingsInRing(level, campfirePos, inv, neededSaplings, 1);
        }

        // small growth nudge so they don't stall on "no trees"
        RoamersCompat.tryAdvanceSaplingsNear(level, campfirePos, 6);

        // Refresh their brain after we changed the environment
        RoamersCompat.refreshAI(roamer);

        setTag(roamer, TAG_STARTKIT_DONE);
    }

    private void assistWithWantedItems(ServerLevel level, Entity roamer, long gameTime) {
        WantedInfo wanted = RoamersCompat.getWantedInfo(roamer);
        if (wanted == null) return;

        Object invObj = RoamersCompat.invoke(roamer, "getInventory");
        if (!(invObj instanceof SimpleContainer inv)) return;

        if (RoamersCompat.containerHas(inv, wanted.item())) return;

        // 4) Prefer taking from the Roamer's start chest, then any nearby chest/container.
        BlockPos prefer = getStartChestPos(roamer);
        boolean pulled = RoamersCompat.pullFromNearbyContainers(level, roamer.blockPosition(), prefer, CHEST_SEARCH_RADIUS, inv, wanted.item(), wanted.type == WantedType.BUILD ? 16 : 8);
        if (pulled) {
            RoamersCompat.refreshAI(roamer);
            return;
        }

        // If it's wood-derived and they have saplings, make sure trees exist (plant near if possible)
        if (RoamersCompat.isWoodDerivedItem(wanted.item())) {
            BlockPos center = roamer.blockPosition();
            RoamersCompat.ensureSomeSaplingsPlanted(level, center, inv, 2);
            RoamersCompat.tryAdvanceSaplingsNear(level, center, 6);
        }

        // 3) Pity system: if they've wanted the same thing for 5 minutes, give it (repeatable per-item cooldown)
        tickPityGive(level, roamer, inv, wanted, gameTime);
    }

    private void tickPityGive(ServerLevel level, Entity roamer, SimpleContainer inv, WantedInfo wanted, long gameTime) {
        String wantedKey = wanted.type + ":" + RoamersCompat.idOfItem(wanted.item());
        CompoundTag root = addonRoot(roamer);

        String prevKey = root.getString(KEY_WANTED);
        long since = root.getLong(KEY_WANTED_SINCE);
        if (!wantedKey.equals(prevKey)) {
            root.putString(KEY_WANTED, wantedKey);
            root.putLong(KEY_WANTED_SINCE, gameTime);
            return;
        }

        if (since == 0L) {
            root.putLong(KEY_WANTED_SINCE, gameTime);
            return;
        }

        long waited = gameTime - since;
        if (waited < AUTO_HELP_AFTER_TICKS) return;

        // per-item cooldown
        CompoundTag pity = root.getCompound(KEY_PITY);
        long last = pity.getLong(wantedKey);
        if (last != 0L && (gameTime - last) < AUTO_HELP_COOLDOWN_TICKS) return;

        int amount = (wanted.type == WantedType.BUILD) ? GIVE_BUILD_AMOUNT : GIVE_CRAFT_AMOUNT;
        RoamersCompat.giveToContainer(inv, new ItemStack(wanted.item(), amount));
        pity.putLong(wantedKey, gameTime);
        root.put(KEY_PITY, pity);

        // reset timer so it can repeat again if they get stuck again later
        root.putLong(KEY_WANTED_SINCE, gameTime);

        RoamersCompat.refreshAI(roamer);
    }

    // ---------------------- Wanted model ----------------------

    public enum WantedType { BUILD, CRAFT, OTHER }

    public record WantedInfo(Item item, WantedType type) {}

    // ---------------------- Utility ----------------------

    private static BlockPos findSafeChestPos(ServerLevel level, BlockPos campfire, BlockPos crafting) {
        // Prefer a spot a few blocks away from campfire to avoid excavation
        BlockPos base = campfire;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos p = base.relative(d, 4);
            if (canPlaceChestAt(level, p)) return p;
        }
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos p = crafting.relative(d, 4);
            if (canPlaceChestAt(level, p)) return p;
        }
        return null;
    }

    private static boolean canPlaceChestAt(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.isAir() || state.getMaterial().isReplaceable();
    }

    private static void placeChest(ServerLevel level, BlockPos pos) {
        if (!canPlaceChestAt(level, pos)) return;
        level.setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState());
    }

    private static BlockPos findTreeBasePlantPos(ServerLevel level, BlockPos brokenLogPos) {
        BlockPos p = brokenLogPos;

        // Walk down to find the base (first non-log below)
        for (int i = 0; i < 12; i++) {
            BlockState below = level.getBlockState(p.below());
            if (!RoamersCompat.isLogBlock(below)) break;
            p = p.below();
        }

        // Plant at the first non-log position (where the base log was)
        return p;
    }
}

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
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDestroyBlockEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.aetria.roamersaddon.RoamersAddonMod.LOGGER;

public final class RoamersAddonEvents {

    private static final String TAG_SPAWN_SAPLINGS_DONE = "roamers_addon_spawn_saplings_done";
    private static final String TAG_STARTKIT_DONE = "roamers_addon_startkit_done";

    private static final String ROOT = "roamers_addon";
    private static final String KEY_WANTED = "wanted_key";
    private static final String KEY_WANTED_SINCE = "wanted_since";
    private static final String KEY_PITY = "pity";
    private static final String KEY_START_CHEST = "start_chest_pos";
    private static final String KEY_NEXT_SAPLING_ATTEMPT = "next_sapling_attempt";

    private static final int INIT_DELAY_TICKS = 20;                   // 1 second after first seen
    private static final int SAPLING_RETRY_TICKS = 20 * 5;            // retry giving spawn saplings every 5 seconds until structures are ready
    private static final int AUTO_HELP_AFTER_TICKS = 20 * 60 * 5;     // 5 minutes
    private static final int AUTO_HELP_COOLDOWN_TICKS = 20 * 60 * 5;  // 5 minutes per same "wanted key"

    private static final int GIVE_BUILD_AMOUNT = 16;
    private static final int GIVE_CRAFT_AMOUNT = 8;

    private static final int CHEST_SEARCH_RADIUS = 12;
    private static final int TREE_SEARCH_RADIUS = 10;

    // Track roamers without leaking worlds
    private final Set<Entity> trackedRoamers = Collections.newSetFromMap(new WeakHashMap<>());
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
    public void onLevelTick(LevelTickEvent.Post event) {
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
            if (e.isRemoved()) {
                trackedRoamers.remove(e);
                continue;
            }
            if (e.level() != serverLevel) continue;
            if (!RoamersCompat.isRoamerEntity(e)) continue;

            UUID id = e.getUUID();

            // 1) Give saplings on spawn (one-time)
            if (shouldRunPendingInit(e, gameTime)) {
                try {
                    applySpawnSaplingsIfNeeded(serverLevel, e, gameTime, true);
                } catch (Throwable t) {
                    LOGGER.error("Failed spawn sapling init for roamer {}", id, t);
                } finally {
                    pendingInitAtGameTime.remove(id);
                }
            }

            // Keep retrying spawn saplings (Roamers may initialize structure data a few seconds after spawn)
            try {
                applySpawnSaplingsIfNeeded(serverLevel, e, gameTime, false);
            } catch (Throwable t) {
                LOGGER.error("Failed spawn sapling retry for roamer {}", id, t);
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
                LOGGER.error("Failed assistance tick for roamer {}", id, t);
            }

            // 3) Consistent pity system with 5 minute cooldown per wanted item
            try {
                tickAutoHelp(serverLevel, e, gameTime);
            } catch (Throwable t) {
                LOGGER.error("Failed auto-help tick for roamer {}", id, t);
            }
        }
    }

    @SubscribeEvent
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;

        Entity target = event.getTarget();
        if (!RoamersCompat.isRoamerEntity(target)) return;

        InteractionHand hand = event.getHand();
        ItemStack held = player.getItemInHand(hand);
        if (!held.is(Items.STICK)) return;

        String msg = buildFriendlyStatusMessage(target);
        player.sendSystemMessage(Component.literal(msg));
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    /**
     * 1) Protect the initial chest from Roamer excavation/breaking.
     * 5) Replant saplings when Roamers chop logs (best-effort).
     */
    @SubscribeEvent
    public void onLivingDestroyBlock(LivingDestroyBlockEvent event) {
        LivingEntity breaker = event.getEntity();
        if (breaker == null) return;
        if (!RoamersCompat.isRoamerEntity(breaker)) return;
        if (!(breaker.level() instanceof ServerLevel level)) return;

        BlockPos pos = event.getPos();
        BlockState state = event.getState();

        // Chest protection
        BlockPos protectedChest = getStartChestPos(breaker);
        if (protectedChest != null && protectedChest.equals(pos)) {
            event.setCanceled(true);
            return;
        }

        // Replant after chopping logs
        if (!RoamersCompat.isLogLike(state)) return;

        long gameTime = level.getGameTime();
        BlockPos plantPos = findTreeBasePlantPos(level, pos);
        if (plantPos == null) return;

        long key = plantPos.asLong();
        Long until = recentReplants.get(key);
        if (until != null && until > gameTime) return;

        Object invObj = RoamersCompat.invoke(breaker, "getInventory");
        if (!(invObj instanceof SimpleContainer inv)) return;

        Item saplingItem = RoamersCompat.saplingForLogBlock(state);
        if (saplingItem == null) return;

        if (!RoamersCompat.removeOne(inv, saplingItem)) return;

        if (RoamersCompat.placeSaplingBlock(level, plantPos, saplingItem)) {
            recentReplants.put(key, gameTime + 20L * 30L); // 30s
            // Optional small growth nudge so they don't stall
            RoamersCompat.tryAdvanceSapling(level, plantPos);
        }
    }

    private boolean shouldRunPendingInit(Entity roamer, long gameTime) {
        Long due = pendingInitAtGameTime.get(roamer.getUUID());
        return due != null && gameTime >= due;
    }

    private static CompoundTag addonRoot(Entity e) {
        CompoundTag pd = e.getPersistentData();
        if (!pd.contains(ROOT)) pd.put(ROOT, new CompoundTag());
        return pd.getCompound(ROOT);
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

        private void applySpawnSaplingsIfNeeded(ServerLevel level, Entity roamer, long gameTime, boolean forceNow) {
        if (hasTag(roamer, TAG_SPAWN_SAPLINGS_DONE)) return;

        // Roamers can finish initializing Land/structure data a few seconds after spawn.
        // Throttle attempts so we don't spam reflection every tick.
        CompoundTag root = addonRoot(roamer);
        if (!forceNow) {
            long nextTry = root.getLong(KEY_NEXT_SAPLING_ATTEMPT);
            if (nextTry != 0L && gameTime < nextTry) return;
        }
        root.putLong(KEY_NEXT_SAPLING_ATTEMPT, gameTime + SAPLING_RETRY_TICKS);

        Set<Item> saplings = RoamersCompat.findSaplingsForRoamerStructures(roamer);
        if (saplings.isEmpty()) return;

        Object invObj = RoamersCompat.invoke(roamer, "getInventory");
        if (!(invObj instanceof SimpleContainer inv)) return;

        for (Item sapling : saplings) {
            int have = RoamersCompat.count(inv, sapling);
            if (have >= 8) continue;
            RoamersCompat.giveToContainer(inv, new ItemStack(sapling, 8 - have));
        }

        // Mark done once we successfully infer saplings + see an inventory.
        setTag(roamer, TAG_SPAWN_SAPLINGS_DONE);
        root.remove(KEY_NEXT_SAPLING_ATTEMPT);
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

        // Ensure the Roamer actually has the inferred saplings before we try to plant them
        applySpawnSaplingsIfNeeded(level, roamer, gameTime, true);

        // 2) plant saplings around camp BEFORE they get deep into building
        // Plant at least 1 of every sapling variant currently carried
        RoamersCompat.plantSaplingsInRing(level, campfirePos, inv, 1);

        // Also plant extra of the wood types used for structures (best effort)
        Set<Item> neededSaplings = RoamersCompat.findSaplingsForRoamerStructures(roamer);
        if (!neededSaplings.isEmpty()) {
            RoamersCompat.plantSpecificSaplingsInRing(level, campfirePos, inv, neededSaplings, 3);
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

        // 4) If it's a wood-derived item, try crafting it from resources they already have.
        if (RoamersCompat.isWoodDerived(wanted.item())) {
            boolean crafted = RoamersCompat.tryCraftWoodDerived(inv, wanted.item());
            if (crafted) {
                RoamersCompat.refreshAI(roamer);
                return;
            }

            // 2) Ensure saplings exist near camp if they have them (so trees can exist early).
            BlockPos campfirePos = RoamersCompat.findBuildStartPos(roamer, "CAMPFIRE");
            BlockPos plantCenter = campfirePos != null ? campfirePos : roamer.blockPosition();

            // If there are no logs nearby, plant saplings and nudge growth.
            if (!RoamersCompat.hasAnyLogsNearby(level, plantCenter, TREE_SEARCH_RADIUS)) {
                RoamersCompat.plantSaplingsInRing(level, plantCenter, inv, 2);
                RoamersCompat.tryAdvanceSaplingsNear(level, plantCenter, 6);
                RoamersCompat.refreshAI(roamer);
                return;
            }

            // If logs exist nearby but they're stalling, force a mining refresh for the wood base.
            Item logItem = RoamersCompat.bestLogForWoodDerived(wanted.item());
            if (logItem != null) {
                RoamersCompat.forceMineForItem(roamer, logItem);
            } else {
                RoamersCompat.refreshAI(roamer);
            }
        }
    }

    /**
     * 3) Pity system that works consistently:
     * - Track "wanted key" + sinceTick
     * - Give after 5 minutes stuck
     * - Repeatable every 5 minutes per same wanted key
     */
    private void tickAutoHelp(ServerLevel level, Entity roamer, long gameTime) {
        WantedInfo wanted = RoamersCompat.getWantedInfo(roamer);
        if (wanted == null) {
            // clear current wanted tracking
            CompoundTag root = addonRoot(roamer);
            root.remove(KEY_WANTED);
            root.remove(KEY_WANTED_SINCE);
            return;
        }

        CompoundTag root = addonRoot(roamer);

        String currentKey = root.getString(KEY_WANTED);
        if (!wanted.key().equals(currentKey)) {
            root.putString(KEY_WANTED, wanted.key());
            root.putLong(KEY_WANTED_SINCE, gameTime);
        }

        long since = root.getLong(KEY_WANTED_SINCE);
        long age = gameTime - since;
        if (age < AUTO_HELP_AFTER_TICKS) return;

        Object invObj = RoamersCompat.invoke(roamer, "getInventory");
        if (!(invObj instanceof SimpleContainer inv)) return;

        // If they already have at least one, don't hand out more.
        if (RoamersCompat.containerHas(inv, wanted.item())) return;

        // Per-key cooldown so it keeps working reliably.
        CompoundTag pity = root.getCompound(KEY_PITY);
        String safeKey = wanted.key().replace(':', '_').replace('/', '_');
        long lastGave = pity.getLong(safeKey);
        if (lastGave > 0L && (gameTime - lastGave) < AUTO_HELP_COOLDOWN_TICKS) return;

        int amount = wanted.type == WantedType.BUILD ? GIVE_BUILD_AMOUNT : GIVE_CRAFT_AMOUNT;
        ItemStack stack = new ItemStack(wanted.item(), amount);
        RoamersCompat.giveToContainer(inv, stack);

        pity.putLong(safeKey, gameTime);
        root.put(KEY_PITY, pity);

        RoamersCompat.refreshAI(roamer);
    }

    private static BlockPos findSafeChestPos(ServerLevel level, BlockPos campfirePos, BlockPos craftingPos) {
        // Prefer a spot a few blocks away from the camp area so excavation doesn't immediately hit it.
        int dx = craftingPos.getX() - campfirePos.getX();
        int dz = craftingPos.getZ() - campfirePos.getZ();
        Direction away;
        if (Math.abs(dx) > Math.abs(dz)) {
            away = dx >= 0 ? Direction.EAST : Direction.WEST;
        } else {
            away = dz >= 0 ? Direction.SOUTH : Direction.NORTH;
        }

        BlockPos preferred = craftingPos.relative(away, 4);

        // search a small ring around preferred
        for (int r = 0; r <= 3; r++) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos p = preferred.relative(d, r);
                if (p.distManhattan(campfirePos) <= 2) continue;
                if (p.distManhattan(craftingPos) <= 2) continue;
                BlockPos adjusted = findSurfaceSpot(level, p);
                if (adjusted != null && isGoodChestSpot(level, adjusted)) return adjusted;
            }
        }

        // fall back: scan around crafting at distance 4
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos p = craftingPos.relative(d, 4);
            BlockPos adjusted = findSurfaceSpot(level, p);
            if (adjusted != null && isGoodChestSpot(level, adjusted)) return adjusted;
        }

        return null;
    }

    private static BlockPos findSurfaceSpot(ServerLevel level, BlockPos pos) {
        // Try to nudge the Y to a good spot (avoid floating chests)
        BlockPos.MutableBlockPos m = pos.mutable();
        // Search downward first
        for (int i = 0; i < 6; i++) {
            if (isGoodChestSpot(level, m)) return m.immutable();
            m.move(Direction.DOWN);
        }
        // Then upward
        m.set(pos);
        for (int i = 0; i < 6; i++) {
            if (isGoodChestSpot(level, m)) return m.immutable();
            m.move(Direction.UP);
        }
        return null;
    }

    private static boolean isGoodChestSpot(ServerLevel level, BlockPos pos) {
        if (!level.isEmptyBlock(pos)) return false;

        BlockPos below = pos.below();
        BlockState belowState = level.getBlockState(below);
        if (!belowState.isFaceSturdy(level, below, Direction.UP)) return false;

        FluidState fluid = level.getFluidState(pos);
        return fluid.isEmpty();
    }

    private static void placeChest(ServerLevel level, BlockPos pos) {
        BlockState state = Blocks.CHEST.defaultBlockState();
        DirectionProperty facingProp = ChestBlock.FACING;
        if (state.hasProperty(facingProp)) {
            state = state.setValue(facingProp, Direction.NORTH);
        }
        level.setBlockAndUpdate(pos, state);
    }

    @SuppressWarnings("SameReturnValue")
    private static BlockPos findTreeBasePlantPos(ServerLevel level, BlockPos brokenLogPos) {
        // Plant where the base log was (or would be) if the ground supports it.
        BlockPos.MutableBlockPos m = brokenLogPos.mutable();

        // Walk down through any logs/air until ground.
        for (int i = 0; i < 12; i++) {
            BlockState below = level.getBlockState(m.below());
            if (!RoamersCompat.isLogLike(below)) {
                // try plant at current position if air and below supports
                BlockPos plant = m.immutable();
                if (!level.isEmptyBlock(plant)) return null;
                if (!below.isFaceSturdy(level, m.below(), Direction.UP)) return null;
                return plant;
            }
            m.move(Direction.DOWN);
        }
        return null;
    }

    private static String buildFriendlyStatusMessage(Entity roamer) {
        String activity = RoamersCompat.getActivityName(roamer);
        WantedInfo wanted = RoamersCompat.getWantedInfo(roamer);

        if (wanted == null) {
            return "Roamer: I'm currently busy (" + activity + ").";
        }

        String itemName = wanted.item().getDescription().getString();
        return switch (wanted.type) {
            case BUILD -> "Roamer: I'm " + activity + " — I'm missing " + itemName + " to keep building.";
            case CRAFT -> "Roamer: I'm " + activity + " — I'm trying to craft something, but I need " + itemName + ".";
        };
    }

    enum WantedType { BUILD, CRAFT }

    record WantedInfo(WantedType type, Item item, String key) { }
}

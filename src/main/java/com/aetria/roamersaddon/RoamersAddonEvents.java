package com.aetria.roamersaddon;

import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.aetria.roamersaddon.RoamersAddonMod.LOGGER;

public final class RoamersAddonEvents {

    private static final String TAG_SPAWN_SAPLINGS_DONE = "roamers_addon_spawn_saplings_done";
    private static final String TAG_STARTKIT_DONE = "roamers_addon_startkit_done";

    private static final int INIT_DELAY_TICKS = 20;      // 1 second after first seen
    private static final int AUTO_HELP_AFTER_TICKS = 20 * 60 * 5; // 5 minutes
    private static final int GIVE_BUILD_AMOUNT = 16;
    private static final int GIVE_CRAFT_AMOUNT = 8;

    // Track per-roamer state without holding strong refs to entities
    private final Set<Entity> trackedRoamers = Collections.newSetFromMap(new WeakHashMap<>());

    private final Map<UUID, RoamerState> stateById = new ConcurrentHashMap<>();
    private final Map<UUID, Long> pendingInitAtGameTime = new ConcurrentHashMap<>();

    @SubscribeEvent
    public void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;

        Entity e = event.getEntity();
        if (!RoamersCompat.isRoamerEntity(e)) return;

        trackedRoamers.add(e);

        UUID id = e.getUUID();
        stateById.computeIfAbsent(id, k -> new RoamerState());

        // Delay init so Roamers has time to finish setting Land/buildings
        long gameTime = event.getLevel().getGameTime();
        pendingInitAtGameTime.putIfAbsent(id, gameTime + INIT_DELAY_TICKS);
    }

    @SubscribeEvent
    public void onLevelTick(LevelTickEvent.Post event) {
        Level level = event.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) return;

        long gameTime = serverLevel.getGameTime();
        if ((gameTime % 20L) != 0L) return; // run once per second
        // Work on a snapshot to avoid concurrent modification.
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
            RoamerState state = stateById.computeIfAbsent(id, k -> new RoamerState());

            // 1) Spawn saplings (one-time)
            if (shouldRunPendingInit(e, gameTime)) {
                try {
                    applySpawnSaplingsIfNeeded(serverLevel, e);
                } catch (Throwable t) {
                    LOGGER.error("Failed spawn sapling init for roamer {}", id, t);
                } finally {
                    pendingInitAtGameTime.remove(id);
                }
            }

            // 4) Place chest + drop one of each sapling variant when campfire+crafting table exist
            try {
                tryPlaceStartKitIfReady(serverLevel, e);
            } catch (Throwable t) {
                LOGGER.error("Failed start-kit placement for roamer {}", id, t);
            }

            
            // 1b) If they are stuck wanting wood-derived items and there are no trees/logs nearby,
            // proactively plant the right saplings (and nudge growth) so they can craft instead of idling.
            try {
                tickWoodSupport(serverLevel, e, state, gameTime);
            } catch (Throwable t) {
                LOGGER.error("Failed wood-support tick for roamer {}", id, t);
            }

// 2) Auto-provide missing item after 5 minutes of looking
            try {
                tickAutoHelp(serverLevel, e, state, gameTime);
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

    @SubscribeEvent
    public void onBlockBreak(net.neoforged.neoforge.event.level.BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        // Only attribute replanting to Roamers when the breaker is a FakePlayer (Roamers uses fake interactions).
        if (!(event.getPlayer() instanceof net.neoforged.neoforge.common.util.FakePlayer)) return;

        BlockPos pos = event.getPos();
        BlockState state = event.getState();
        if (!RoamersCompat.isLogBlock(state)) return;

        // Find the nearest Roamer to this break position.
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(pos).inflate(5.0);
        List<Entity> nearby = level.getEntitiesOfClass(Entity.class, box, RoamersCompat::isRoamerEntity);
        if (nearby.isEmpty()) return;

        Entity closest = null;
        double best = Double.MAX_VALUE;
        for (Entity e : nearby) {
            double d = e.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            if (d < best) {
                best = d;
                closest = e;
            }
        }
        if (closest == null) return;

        // Schedule for next tick (after the log is actually removed) to avoid placement failures.
        level.getServer().execute(() -> {
            try {
                RoamersCompat.tryReplantAfterLogBreak(level, closest, pos, state);
            } catch (Throwable t) {
                LOGGER.error("Failed replant after log break at {}", pos, t);
            }
        });
    }



    private boolean shouldRunPendingInit(Entity roamer, long gameTime) {
        Long due = pendingInitAtGameTime.get(roamer.getUUID());
        return due != null && gameTime >= due;
    }

    private static CompoundTag persistentData(Entity e) {
        // NeoForge entities expose persistent data via getPersistentData()
        return e.getPersistentData();
    }

    private static boolean hasTag(Entity e, String key) {
        return persistentData(e).getBoolean(key);
    }

    private static void setTag(Entity e, String key) {
        persistentData(e).putBoolean(key, true);
    }

    
    private void applySpawnSaplingsIfNeeded(ServerLevel level, Entity roamer) {
        if (hasTag(roamer, TAG_SPAWN_SAPLINGS_DONE)) return;

        // Figure out which saplings match the wood types used in the Roamer's structures.
        // IMPORTANT: this must work immediately on spawn (before Land/buildings are selected),
        // so RoamersCompat includes a Race-based fallback.
        Set<Item> saplings = RoamersCompat.findSaplingsForRoamerStructures(roamer);
        if (saplings == null || saplings.isEmpty()) {
            // Last-resort fallback (should be rare): Plains defaults.
            saplings = new LinkedHashSet<>();
            saplings.add(Items.OAK_SAPLING);
            saplings.add(Items.BIRCH_SAPLING);
        }

        Object invObj = RoamersCompat.invoke(roamer, "getInventory");
        if (!(invObj instanceof net.minecraft.world.SimpleContainer inv)) return;

        // Top up to 8 of each sapling (don't spam-stack every restart).
        for (Item sapling : saplings) {
            int have = RoamersCompat.count(inv, sapling);
            int want = 8;
            if (have >= want) continue;
            RoamersCompat.giveToContainer(inv, new ItemStack(sapling, want - have));
        }

        setTag(roamer, TAG_SPAWN_SAPLINGS_DONE);
    }

        Object invObj = RoamersCompat.invoke(roamer, "getInventory");
        if (!(invObj instanceof net.minecraft.world.SimpleContainer inv)) return;

        for (Item sapling : saplings) {
            RoamersCompat.giveToContainer(inv, new ItemStack(sapling, 8));
        }

        setTag(roamer, TAG_SPAWN_SAPLINGS_DONE);
    }

    private void tryPlaceStartKitIfReady(ServerLevel level, Entity roamer) {
        if (hasTag(roamer, TAG_STARTKIT_DONE)) return;

        BlockPos campfirePos = RoamersCompat.findBuildStartPos(roamer, "CAMPFIRE");
        BlockPos craftingPos = RoamersCompat.findBuildStartPos(roamer, "CRAFTING_TABLE");
        if (campfirePos == null || craftingPos == null) return;

        BlockState campfireState = level.getBlockState(campfirePos);
        BlockState craftingState = level.getBlockState(craftingPos);
        if (!campfireState.is(Blocks.CAMPFIRE) || !craftingState.is(Blocks.CRAFTING_TABLE)) return;

        // Place a chest near the crafting table
        BlockPos chestPos = findNearbyChestPos(level, craftingPos);
        if (chestPos == null) return;

        placeChest(level, chestPos);

        
        // Ensure saplings exist in inventory before we try to plant them.
        applySpawnSaplingsIfNeeded(level, roamer);

        Object invObj = RoamersCompat.invoke(roamer, "getInventory");
        if (invObj instanceof net.minecraft.world.SimpleContainer inv) {
            // Plant saplings immediately around camp (prioritize the wood-types needed for structures).
            Set<Item> neededSaplings = RoamersCompat.findSaplingsForRoamerStructures(roamer);
            if (neededSaplings != null && !neededSaplings.isEmpty()) {
                // Plant 2 of each needed type first (so they can produce wood quickly)
                RoamersCompat.plantSpecificSaplingsInRing(level, campfirePos, inv, neededSaplings, 2);
            }

            // Then plant 1 of any other sapling variants they carry.
            RoamersCompat.plantSaplingsInRing(level, campfirePos, inv, 1);

            // Nudge nearby saplings so they don't stall on "no trees"
            RoamersCompat.tryAdvanceSaplingsNear(level, campfirePos, 6);
        }
setTag(roamer, TAG_STARTKIT_DONE);
    }

    private void tickAutoHelp(ServerLevel level, Entity roamer, RoamerState state, long gameTime) {
        WantedInfo wanted = RoamersCompat.getWantedInfo(roamer);
        if (wanted == null) {
            state.clear();
            return;
        }

        if (!wanted.key().equals(state.lastWantedKey)) {
            state.lastWantedKey = wanted.key();
            state.firstSeenTick = gameTime;
            // Reset "already gave" for new key
            state.lastGaveKey = null;
        }

        long age = gameTime - state.firstSeenTick;
        if (age < AUTO_HELP_AFTER_TICKS) return;
        if (wanted.key().equals(state.lastGaveKey)) return;

        Object invObj = RoamersCompat.invoke(roamer, "getInventory");
        if (!(invObj instanceof net.minecraft.world.SimpleContainer inv)) return;

        // If they already have at least one, don't hand out more.
        if (RoamersCompat.containerHas(inv, wanted.item())) return;

        int amount = wanted.type == WantedType.BUILD ? GIVE_BUILD_AMOUNT : GIVE_CRAFT_AMOUNT;
        ItemStack stack = new ItemStack(wanted.item(), amount);
        RoamersCompat.giveToContainer(inv, stack);

        state.lastGaveKey = wanted.key();
    }

    private void tickWoodSupport(ServerLevel level, Entity roamer, RoamerState state, long gameTime) {
        WantedInfo wanted = RoamersCompat.getWantedInfo(roamer);
        if (wanted == null || wanted.item() == null) return;

        // Don't run constantly - prevent spam planting
        if (state.lastWoodAssistTick != 0L && (gameTime - state.lastWoodAssistTick) < 200L) return; // 10s

        ResourceLocation id = BuiltInRegistries.ITEM.getKey(wanted.item());
        if (id == null) return;

        if (!isWoodRelatedPath(id.getPath())) return;

        Object invObj = RoamersCompat.invoke(roamer, "getInventory");
        if (!(invObj instanceof net.minecraft.world.SimpleContainer inv)) return;

        BlockPos center = roamer.blockPosition();

        // If there are already logs nearby, let them chop/craft normally.
        if (RoamersCompat.hasAnyLogNearby(level, center, 10)) return;

        Set<Item> toPlant = new LinkedHashSet<>();
        Item specific = RoamersCompat.saplingForWantedItem(wanted.item());
        if (specific != null && specific != Items.AIR) {
            toPlant.add(specific);
        } else {
            Set<Item> inferred = RoamersCompat.findSaplingsForRoamerStructures(roamer);
            if (inferred != null) toPlant.addAll(inferred);
        }

        if (toPlant.isEmpty()) return;

        // Plant 2 of each needed type and nudge growth.
        RoamersCompat.plantSpecificSaplingsInRing(level, center, inv, toPlant, 2);
        RoamersCompat.tryAdvanceSaplingsNear(level, center, 6);

        state.lastWoodAssistTick = gameTime;
    }

    private static boolean isWoodRelatedPath(String path) {
        if (path == null) return false;
        // Typical build/craft needs that should trigger "plant trees"
        return path.contains("plank")
                || path.contains("slab")
                || path.contains("stairs")
                || path.contains("fence")
                || path.contains("gate")
                || path.contains("log")
                || path.contains("wood")
                || path.contains("door")
                || path.contains("trapdoor")
                || path.contains("button")
                || path.contains("pressure_plate")
                || path.contains("sign")
                || path.contains("hanging_sign")
                || path.contains("boat")
                || path.contains("chest")
                || path.contains("barrel");
    }



    private static BlockPos findNearbyChestPos(ServerLevel level, BlockPos near) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos p = near.relative(dir);
            if (isGoodChestSpot(level, p)) return p;
        }
        // Try one block further out
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos p = near.relative(dir, 2);
            if (isGoodChestSpot(level, p)) return p;
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
        // set a sane facing if the property exists
        DirectionProperty facingProp = ChestBlock.FACING;
        if (state.hasProperty(facingProp)) {
            state = state.setValue(facingProp, Direction.NORTH);
        }
        level.setBlockAndUpdate(pos, state);
    }

    private static void spawnItem(ServerLevel level, BlockPos pos, ItemStack stack) {
        ItemEntity ent = new ItemEntity(level,
                pos.getX() + 0.5,
                pos.getY() + 1.1,
                pos.getZ() + 0.5,
                stack);
        ent.setDefaultPickUpDelay();
        level.addFreshEntity(ent);
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

    private static final class RoamerState {
        String lastWantedKey = null;
        long firstSeenTick = 0L;
        String lastGaveKey = null;

        long lastWoodAssistTick = 0L;

        void clear() {
            lastWantedKey = null;
            firstSeenTick = 0L;
            lastGaveKey = null;
            lastWoodAssistTick = 0L;
        }
    }

enum WantedType { BUILD, CRAFT }

    record WantedInfo(WantedType type, Item item, String key) { }
}

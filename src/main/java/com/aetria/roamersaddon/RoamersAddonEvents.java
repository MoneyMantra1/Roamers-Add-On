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
    private static final String TAG_SPAWN_PLANT_DONE = "roamers_addon_spawn_plant_done";
    private static final String TAG_STARTKIT_DONE = "roamers_addon_startkit_done";

    private static final int INIT_DELAY_TICKS = 60;      // 3 seconds after first seen (gives Roamers time to pick buildings/materials)
    private static final int AUTO_HELP_AFTER_TICKS = 20 * 60 * 5; // 5 minutes
    private static final int GIVE_BUILD_AMOUNT = 16;
    private static final int GIVE_CRAFT_AMOUNT = 8;

    // Tree self-sufficiency tuning
    private static final int SPAWN_SAPLINGS_PER_TYPE = 8;
    private static final int SPAWN_BONEMEAL = 64;
    // On spawn: plant a full "starter grove" so roamers don't stall later in treeless worlds.
    // Goal is "8 of each" sapling type in the roamer's palette.
    private static final int SPAWN_PLANT_GOAL_PER_TYPE = 8;
    private static final int SPAWN_PLANT_RANGE = 20; // 40x40 square (20 blocks each direction)
    private static final int BUILD_AVOID_RADIUS = 8;
    // While stuck on wood: check often enough to feel responsive, but still lightweight.
    private static final int WOOD_HELP_CHECK_INTERVAL_TICKS = 20 * 5; // every 5 seconds at most
    private static final int WOOD_LOG_SCAN_RADIUS = 10;
    private static final int BONEMEAL_ATTEMPTS_PER_SECOND = 4;

    // When blocked by a wood-derived requirement, ensure we have multiple saplings placed so at least one can grow.
    // User goal: if one sapling exists but can't grow, place at least 3 more -> target 4 active saplings.
    private static final int WOOD_HELP_TARGET_PLANTED_SAPLINGS = 4;

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
                    applySpawnSaplingsIfNeeded(serverLevel, e, state);
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

            // 2) Auto-provide missing item after 5 minutes of looking
            try {
                // 1/4) Wood self-sufficiency: plant + (optionally) bonemeal saplings when wood-derived items are blocking progress.
                tickWoodSelfSufficiency(serverLevel, e, state, gameTime);

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

    private void applySpawnSaplingsIfNeeded(ServerLevel level, Entity roamer, RoamerState state) {
        // Step 1: ensure they START with enough saplings + bonemeal.
        if (!hasTag(roamer, TAG_SPAWN_SAPLINGS_DONE)) {
            // Figure out which saplings match the wood types used in the Roamer's structures.
            Set<Item> saplings = RoamersCompat.findSaplingsForRoamerStructures(roamer);
            if (saplings.isEmpty()) {
                // Not fatal; it just means we couldn't infer anything yet.
                return;
            }

            Object invObj = RoamersCompat.invoke(roamer, "getInventory");
            if (!(invObj instanceof net.minecraft.world.SimpleContainer inv)) return;

            for (Item sapling : saplings) {
                RoamersCompat.giveToContainer(inv, new ItemStack(sapling, SPAWN_SAPLINGS_PER_TYPE));
            }
            RoamersCompat.giveToContainer(inv, new ItemStack(Items.BONE_MEAL, SPAWN_BONEMEAL));

            // Remember the inferred saplings for later (wood self-sufficiency)
            state.spawnSaplings = saplings;

            setTag(roamer, TAG_SPAWN_SAPLINGS_DONE);
        }

        // Step 2: plant a balanced mix once, outside the build footprint.
        if (hasTag(roamer, TAG_SPAWN_PLANT_DONE)) return;

        Object invObj = RoamersCompat.invoke(roamer, "getInventory");
        if (!(invObj instanceof net.minecraft.world.SimpleContainer inv)) return;

        Set<Item> saplings = state.spawnSaplings != null ? state.spawnSaplings : RoamersCompat.findSaplingsForRoamerStructures(roamer);
        if (saplings.isEmpty()) return;

        // "8 of each" means the total is palette_size * goal_per_type.
        // We keep placement safe via spacing + maxAttempts inside RoamersCompat.
        int maxTotal = Math.max(1, SPAWN_PLANT_GOAL_PER_TYPE * saplings.size());

        List<BlockPos> buildStarts = RoamersCompat.getAllBuildStartPositions(roamer);
        BlockPos center = RoamersCompat.findBuildStartPos(roamer, "CRAFTING_TABLE");
        if (center == null) center = roamer.blockPosition();

        Map<Item, List<BlockPos>> planted = RoamersCompat.plantSpecificSaplingsScattered(
                level,
                center,
                inv,
                saplings,
                SPAWN_PLANT_GOAL_PER_TYPE,
                maxTotal,
                SPAWN_PLANT_RANGE,
                buildStarts,
                BUILD_AVOID_RADIUS
        );

        if (!planted.isEmpty()) {
            state.recordPlanted(planted);
        }

        setTag(roamer, TAG_SPAWN_PLANT_DONE);
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

        // Drop 1 of each sapling variant the roamer currently carries (remove from inventory if possible)
        Object invObj = RoamersCompat.invoke(roamer, "getInventory");
        if (invObj instanceof net.minecraft.world.SimpleContainer inv) {
            List<Item> saplingItems = RoamersCompat.listSaplingVariantsInContainer(inv);
            for (Item sapling : saplingItems) {
                if (RoamersCompat.removeOne(inv, sapling)) {
                    spawnItem(level, chestPos, new ItemStack(sapling, 1));
                } else {
                    // If we couldn't remove, still drop 1 to satisfy the feature
                    spawnItem(level, chestPos, new ItemStack(sapling, 1));
                }
            }
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

    /**
     * Reduce reliance on the "pity" system for wood-derived needs by making sapling placement + growth proactive.
     *
     * Design goals:
     * - Keep CPU impact low (runs 1x/sec, only does work when a wood-derived item is blocking progress)
     * - Never spam plant/bonemeal attempts (cooldowns + small caps)
     */
    private void tickWoodSelfSufficiency(ServerLevel level, Entity roamer, RoamerState state, long gameTime) {
        WantedInfo wanted = RoamersCompat.getWantedInfo(roamer);
        if (wanted == null) return;
        if (!RoamersCompat.isWoodDerived(wanted.item())) return;

        Object invObj = RoamersCompat.invoke(roamer, "getInventory");
        if (!(invObj instanceof net.minecraft.world.SimpleContainer inv)) return;

        // If they already have it, nothing to do.
        if (RoamersCompat.containerHas(inv, wanted.item())) return;

        boolean didSomething = false;

        // If they have the materials in-inventory, craft (planks/slabs/stairs/etc) instead of idling.
        if (RoamersCompat.tryCraftWoodDerived(inv, wanted.item())) {
            didSomething = true;
        }

        // If crafting produced the needed item, don't start planting/bonemealing.
        if (RoamersCompat.containerHas(inv, wanted.item())) {
            if (didSomething) RoamersCompat.refreshAI(roamer);
            return;
        }

        Item neededSapling = RoamersCompat.saplingForWoodDerived(wanted.item());
        if (neededSapling == null) {
            if (didSomething) RoamersCompat.refreshAI(roamer);
            return;
        }

        // Keep a small set of tracked saplings we planted, so we can bonemeal them without scanning the world.
        // Also drop "bad" sapling spots (e.g., planted under a solid ceiling) so we can replant elsewhere.
        state.pruneSaplingPositions(level, neededSapling);

        int activeSaplings = state.getSaplingPositions(neededSapling).size();
        int haveSaplings = RoamersCompat.count(inv, neededSapling);

        // If the roamer is blocked on a wood-derived need and cannot find trees of that wood family,
        // ensure multiple saplings are planted so at least one has a chance to grow.
        // User expectation: if one sapling exists but can't grow, place at least 3 more => target 4.
        if (gameTime >= state.nextWoodHelpTick
                && haveSaplings > 0
                && activeSaplings < WOOD_HELP_TARGET_PLANTED_SAPLINGS) {

            int toPlant = Math.min(WOOD_HELP_TARGET_PLANTED_SAPLINGS - activeSaplings, haveSaplings);
            if (toPlant > 0) {
                List<BlockPos> buildStarts = RoamersCompat.getAllBuildStartPositions(roamer);
                BlockPos center = RoamersCompat.findBuildStartPos(roamer, "CRAFTING_TABLE");
                if (center == null) center = roamer.blockPosition();

                Map<Item, List<BlockPos>> planted = RoamersCompat.plantSpecificSaplingsScattered(
                        level,
                        center,
                        inv,
                        Set.of(neededSapling),
                        toPlant,
                        toPlant,
                        SPAWN_PLANT_RANGE,
                        buildStarts,
                        BUILD_AVOID_RADIUS
                );
                if (!planted.isEmpty()) {
                    state.recordPlanted(planted);
                    didSomething = true;
                }
            }

            state.nextWoodHelpTick = gameTime + WOOD_HELP_CHECK_INTERVAL_TICKS;
        }

        // Bonemeal a few tracked saplings to accelerate growth.
        if (RoamersCompat.count(inv, Items.BONE_MEAL) > 0) {
            List<BlockPos> positions = new ArrayList<>(state.getSaplingPositions(neededSapling));
            if (!positions.isEmpty()) {
                int attempts = Math.min(BONEMEAL_ATTEMPTS_PER_SECOND, RoamersCompat.count(inv, Items.BONE_MEAL));
                for (BlockPos pos : positions) {
                    if (attempts <= 0) break;

                    // If it already grew or got replaced, stop tracking it.
                    if (!RoamersCompat.isExactSaplingAt(level, pos, neededSapling)) {
                        state.removeSaplingPos(neededSapling, pos);
                        continue;
                    }

                    if (RoamersCompat.tryBonemealAt(level, pos, level.getRandom())) {
                        if (RoamersCompat.removeOne(inv, Items.BONE_MEAL)) {
                            attempts--;
                        }
                        didSomething = true;
                    }

                    // If it grew from bonemeal, it won't be a sapling anymore.
                    if (!RoamersCompat.isExactSaplingAt(level, pos, neededSapling)) {
                        state.removeSaplingPos(neededSapling, pos);
                    }
                }
            }
        }

        if (didSomething) {
            RoamersCompat.refreshAI(roamer);
        }
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

        // Cached spawn inference
        Set<Item> spawnSaplings = null;

        // Saplings this add-on planted (per sapling type), used to target bonemeal without scanning large areas.
        private final Map<Item, Set<BlockPos>> plantedSaplings = new HashMap<>();

        // Rate-limit extra planting attempts while stuck on wood-derived needs
        long nextWoodHelpTick = 0L;

        void clear() {
            lastWantedKey = null;
            firstSeenTick = 0L;
            lastGaveKey = null;
        }

        void recordPlanted(Map<Item, List<BlockPos>> planted) {
            for (Map.Entry<Item, List<BlockPos>> e : planted.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                plantedSaplings.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>()).addAll(e.getValue());
            }
        }

        Set<BlockPos> getSaplingPositions(Item sapling) {
            return plantedSaplings.getOrDefault(sapling, Collections.emptySet());
        }

        void removeSaplingPos(Item sapling, BlockPos pos) {
            Set<BlockPos> set = plantedSaplings.get(sapling);
            if (set == null) return;
            set.remove(pos);
            if (set.isEmpty()) plantedSaplings.remove(sapling);
        }

        void pruneSaplingPositions(ServerLevel level, Item sapling) {
            Set<BlockPos> set = plantedSaplings.get(sapling);
            if (set == null || set.isEmpty()) return;
            // Copy to avoid concurrent modification
            List<BlockPos> copy = new ArrayList<>(set);
            for (BlockPos pos : copy) {
                if (!RoamersCompat.isExactSaplingAt(level, pos, sapling)
                        || !RoamersCompat.isSaplingSpotLikelyGrowable(level, pos)) {
                    set.remove(pos);
                }
            }
            if (set.isEmpty()) plantedSaplings.remove(sapling);
        }
    }

    enum WantedType { BUILD, CRAFT }

    record WantedInfo(WantedType type, Item item, String key) { }
}

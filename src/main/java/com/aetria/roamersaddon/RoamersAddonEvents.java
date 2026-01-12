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
        Set<Item> saplings = RoamersCompat.findSaplingsForRoamerStructures(roamer);
        if (saplings.isEmpty()) {
            // Not fatal; it just means we couldn't infer anything yet.
            return;
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

        void clear() {
            lastWantedKey = null;
            firstSeenTick = 0L;
            lastGaveKey = null;
        }
    }

    enum WantedType { BUILD, CRAFT }

    record WantedInfo(WantedType type, Item item, String key) { }
}

package com.aetria.roamersaddon;

import com.aetria.roamersaddon.RoamersAddonEvents.WantedInfo;
import com.aetria.roamersaddon.RoamersAddonEvents.WantedType;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.Container;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

import static com.aetria.roamersaddon.RoamersAddonMod.LOGGER;

/**
 * All Roamers integration is done via reflection so this add-on can stay fully separate.
 */
public final class RoamersCompat {

    private static final ResourceLocation ROAMER_ENTITY_ID = ResourceLocation.fromNamespaceAndPath("roamers", "roamer");

    // Suffixes we consider to represent a "wood set" (used to infer saplings + identify wood-derived needs)
    private static final List<String> WOOD_SUFFIXES = List.of(
            "_log", "_wood", "_stem", "_hyphae",
            "_planks",
            "_stairs", "_slab",
            "_fence", "_fence_gate",
            "_door", "_trapdoor",
            "_pressure_plate", "_button"
    );

    private static final List<String> SAPLING_SUFFIXES = List.of(
            "_sapling",
            "_propagule", // mangrove
            "_fungus"     // nether "saplings"
    );

    /**
     * Roamers populates starting inventory from datapack JSON under data/roamers/start_gear/.
     * Those files do NOT include saplings (campfire/crafting tools/food/etc only), so a freshly-spawned Roamer will
     * naturally have zero saplings unless an add-on provides them.
     *
     * Our primary sapling inference comes from the Roamer's selected building materials, but that often isn't ready
     * in the first few seconds after spawn (buildings are selected later by AI). This race-based fallback keeps the
     * Roamer from stalling immediately in treeless areas.
     */
    private static final Map<String, List<ResourceLocation>> RACE_DEFAULT_SAPLINGS = Map.of(
            "PLAINS", List.of(
                    ResourceLocation.fromNamespaceAndPath("minecraft", "oak_sapling"),
                    ResourceLocation.fromNamespaceAndPath("minecraft", "birch_sapling")
            ),
            "SAVANNA", List.of(
                    ResourceLocation.fromNamespaceAndPath("minecraft", "acacia_sapling"),
                    ResourceLocation.fromNamespaceAndPath("minecraft", "oak_sapling")
            ),
            "JUNGLE", List.of(
                    ResourceLocation.fromNamespaceAndPath("minecraft", "jungle_sapling"),
                    ResourceLocation.fromNamespaceAndPath("minecraft", "oak_sapling")
            ),
            "TAIGA", List.of(
                    ResourceLocation.fromNamespaceAndPath("minecraft", "spruce_sapling")
            ),
            "CHERRY", List.of(
                    ResourceLocation.fromNamespaceAndPath("minecraft", "cherry_sapling")
            )
    );

    // Prevent spam-replanting (e.g., multiple logs from the same tree in rapid succession).
    private static final java.util.Map<java.util.UUID, Long> LAST_REPLANT_TICK = new java.util.concurrent.ConcurrentHashMap<>();



    
    private static Set<Item> fallbackSaplingsFromRace(Entity roamer) {
        try {
            // Prefer the Roamers API: AbstractCharacter#getRace()
            Object raceObj = null;
            try {
                raceObj = invoke(roamer, "getRace");
            } catch (Throwable ignored) {
            }

            // If race isn't set yet, infer from the current blockpos (AbstractCharacter#getRaceFromBlockPos)
            if (raceObj == null) {
                try {
                    raceObj = invoke(roamer, "getRaceFromBlockPos", roamer.blockPosition());
                } catch (Throwable ignored) {
                }
            }

            if (raceObj == null) return Set.of();

            String raceName = null;
            if (raceObj instanceof Enum<?> e) raceName = e.name();
            else raceName = String.valueOf(raceObj);

            if (raceName == null || raceName.isBlank()) return Set.of();

            List<ResourceLocation> ids = RACE_DEFAULT_SAPLINGS.get(raceName);
            if (ids == null || ids.isEmpty()) return Set.of();

            Set<Item> out = new LinkedHashSet<>();
            for (ResourceLocation id : ids) {
                if (BuiltInRegistries.ITEM.containsKey(id)) {
                    Item item = BuiltInRegistries.ITEM.get(id);
                    if (item != null && item != Items.AIR) out.add(item);
                }
            }
            return out;
        } catch (Throwable t) {
            return Set.of();
        }
    }


    private RoamersCompat() {}

    public static boolean isRoamerEntity(Entity e) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
        return ROAMER_ENTITY_ID.equals(id);
    }

    // ---------------------- Reflection helpers ----------------------

    public static Object invoke(Object target, String methodName, Object... args) {
        try {
            Class<?> cls = target.getClass();
            Method m = findMethod(cls, methodName, args);
            if (m == null) return null;
            m.setAccessible(true);
            return m.invoke(target, args);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method findMethod(Class<?> cls, String name, Object[] args) {
        Method[] methods = cls.getMethods();
        outer:
        for (Method m : methods) {
            if (!m.getName().equals(name)) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length != args.length) continue;
            for (int i = 0; i < p.length; i++) {
                if (args[i] == null) continue;
                if (!wrap(p[i]).isAssignableFrom(wrap(args[i].getClass()))) continue outer;
            }
            return m;
        }
        methods = cls.getDeclaredMethods();
        outer2:
        for (Method m : methods) {
            if (!m.getName().equals(name)) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length != args.length) continue;
            for (int i = 0; i < p.length; i++) {
                if (args[i] == null) continue;
                if (!wrap(p[i]).isAssignableFrom(wrap(args[i].getClass()))) continue outer2;
            }
            return m;
        }
        return null;
    }

    private static Class<?> wrap(Class<?> c) {
        if (!c.isPrimitive()) return c;
        if (c == boolean.class) return Boolean.class;
        if (c == byte.class) return Byte.class;
        if (c == short.class) return Short.class;
        if (c == int.class) return Integer.class;
        if (c == long.class) return Long.class;
        if (c == float.class) return Float.class;
        if (c == double.class) return Double.class;
        if (c == char.class) return Character.class;
        return c;
    }

    private static Object getField(Object obj, String fieldName) {
        try {
            Field f = findField(obj.getClass(), fieldName);
            if (f == null) return null;
            f.setAccessible(true);
            return f.get(obj);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field findField(Class<?> cls, String name) {
        try {
            return cls.getField(name);
        } catch (NoSuchFieldException e) {
            // fall through
        }
        try {
            return cls.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    // ---------------------- Roamers-specific helpers ----------------------

    public static Set<Item> findSaplingsForRoamerStructures(Entity roamer) {
        // Primary: infer from the Roamer's currently-selected buildings/materials (when available).
        try {
            Object land = invoke(roamer, "getLand");
            if (land != null) {
                Object buildingsObj = invoke(land, "getBuildings");
                if (buildingsObj instanceof Map<?, ?> buildings) {
                    Set<ResourceLocation> saplingIds = new HashSet<>();

                    for (Object v : buildings.values()) {
                        if (!(v instanceof Pair<?, ?> pair)) continue;
                        Object materialsObj = pair.getSecond();
                        if (materialsObj == null) continue;

                        Object materialsMapObj = getField(materialsObj, "materials");
                        if (!(materialsMapObj instanceof Map<?, ?> mats)) continue;

                        for (Object stateObj : mats.values()) {
                            if (!(stateObj instanceof BlockState state)) continue;
                            Block block = state.getBlock();
                            ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(block);
                            if (blockId == null) continue;

                            String base = extractWoodBase(blockId.getPath());
                            if (base == null) continue;
                            base = stripKnownPrefixes(base);

                            for (String suffix : SAPLING_SUFFIXES) {
                                ResourceLocation candidate = ResourceLocation.fromNamespaceAndPath(blockId.getNamespace(), base + suffix);
                                if (BuiltInRegistries.ITEM.containsKey(candidate)) {
                                    saplingIds.add(candidate);
                                    break;
                                }
                            }
                        }
                    }

                    if (!saplingIds.isEmpty()) {
                        Set<Item> out = new LinkedHashSet<>();
                        for (ResourceLocation id : saplingIds) {
                            Item item = BuiltInRegistries.ITEM.get(id);
                            if (item != null && item != Items.AIR) out.add(item);
                        }
                        if (!out.isEmpty()) return out;
                    }
                }
            }
        } catch (Throwable t) {
            // Fall back below.
            LOGGER.debug("Sapling inference failed: {}", t.toString());
        }

        // Fallback: Roamers doesn't include saplings in its start_gear JSON.
        // When buildings aren't selected yet (very common right after spawn), infer from Race defaults.
        return fallbackSaplingsFromRace(roamer);
    }

    public static BlockPos findBuildStartPos(Entity roamer, String buildTypeName) {
        try {
            Object land = invoke(roamer, "getLand");
            if (land == null) return null;

            Class<?> buildTypeCls = Class.forName("net.caitie.roamers.entity.ai.building.Land$BuildType");
            @SuppressWarnings("unchecked")
            Object buildType = Enum.valueOf((Class<Enum>) buildTypeCls.asSubclass(Enum.class), buildTypeName);

            Method getBuild = land.getClass().getMethod("getBuild", buildTypeCls);
            Object pairObj = getBuild.invoke(land, buildType);
            if (!(pairObj instanceof Pair<?, ?> pair)) return null;

            Object dataObj = pair.getFirst();
            if (dataObj == null) return null;

            Object startPosObj = getField(dataObj, "startPos");
            if (startPosObj instanceof BlockPos pos) return pos;

            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    public static String getActivityName(Entity roamer) {
        try {
            Object act = invoke(roamer, "getCurrentActivity");
            if (act == null) return "doing something";
            String raw = act.toString().toLowerCase(Locale.ROOT);

            if (raw.contains("build")) return "building";
            if (raw.contains("mine") || raw.contains("excavat")) return "excavating";
            if (raw.contains("craft")) return "crafting";
            if (raw.contains("sleep")) return "resting";
            if (raw.contains("wander") || raw.contains("idle")) return "wandering";
            return raw.replace('_', ' ');
        } catch (Throwable t) {
            return "doing something";
        }
    }

    public static WantedInfo getWantedInfo(Entity roamer) {
        try {
            Object wantedBlockObj = invoke(roamer, "getWantedBuildingBlock");
            if (wantedBlockObj instanceof Block b) {
                Item asItem = b.asItem();
                if (asItem != null && asItem != Items.AIR) {
                    ResourceLocation id = BuiltInRegistries.ITEM.getKey(asItem);
                    String key = "build:" + (id == null ? asItem.toString() : id.toString());
                    return new WantedInfo(WantedType.BUILD, asItem, key);
                }
            }

            Object wantedItemObj = invoke(roamer, "getWantedCraftingItem");
            if (wantedItemObj instanceof Item item) {
                if (item != Items.AIR) {
                    ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
                    String key = "craft:" + (id == null ? item.toString() : id.toString());
                    return new WantedInfo(WantedType.CRAFT, item, key);
                }
            }

            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------- AI nudges ----------------------

    public static void refreshAI(Entity roamer) {
        try {
            Object ai = invoke(roamer, "getAI");
            if (ai == null) return;

            // Prefer stronger refresh methods if present
            invoke(ai, "reassessTasks");
            invoke(ai, "reassessGoals");
            invoke(ai, "refreshBrain");
        } catch (Throwable ignored) {
        }
    }

    public static void forceMineForItem(Entity roamer, Item wantedItem) {
        try {
            Object ai = invoke(roamer, "getAI");
            if (ai == null) return;

            Class<?> plc = Class.forName("net.caitie.roamers.entity.PlayerLikeCharacter");
            Class<?> mineTaskCls = Class.forName("net.caitie.roamers.entity.ai.tasks.MineTask");

            Constructor<?> ctor = null;
            for (Constructor<?> c : mineTaskCls.getConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 3 && plc.isAssignableFrom(p[0]) && Item.class.isAssignableFrom(p[1]) && p[2] == boolean.class) {
                    ctor = c;
                    break;
                }
            }
            if (ctor == null) return;

            Object mineTask = ctor.newInstance(roamer, wantedItem, true);
            invoke(ai, "reassessMineTask", mineTask);

            // Switch to MINING brain state if possible
            Class<?> stateCls = Class.forName("net.caitie.roamers.entity.ai.PlayerLikeAI$State");
            @SuppressWarnings("unchecked")
            Object mining = Enum.valueOf((Class<Enum>) stateCls.asSubclass(Enum.class), "MINING");
            invoke(ai, "switchCurrentState", mining);
        } catch (Throwable ignored) {
        }
    }

    // ---------------------- Container helpers ----------------------

    public static boolean containerHas(SimpleContainer inv, Item item) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.getItem() == item) return true;
        }
        return false;
    }

    public static int count(SimpleContainer inv, Item item) {
        int c = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.getItem() == item) c += s.getCount();
        }
        return c;
    }

    public static void giveToContainer(SimpleContainer inv, ItemStack stack) {
        if (stack.isEmpty()) return;

        // Try merge
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack existing = inv.getItem(i);
            if (existing.isEmpty()) continue;
            if (!ItemStack.isSameItemSameComponents(existing, stack)) continue;

            int canAdd = Math.min(existing.getMaxStackSize() - existing.getCount(), stack.getCount());
            if (canAdd <= 0) continue;
            existing.grow(canAdd);
            stack.shrink(canAdd);
            inv.setItem(i, existing);
            if (stack.isEmpty()) return;
        }

        // Put into empty slots
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack existing = inv.getItem(i);
            if (!existing.isEmpty()) continue;

            int put = Math.min(stack.getMaxStackSize(), stack.getCount());
            ItemStack placed = stack.copy();
            placed.setCount(put);
            inv.setItem(i, placed);
            stack.shrink(put);
            if (stack.isEmpty()) return;
        }
    }

    public static boolean removeOne(SimpleContainer inv, Item item) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            if (s.getItem() != item) continue;

            s.shrink(1);
            inv.setItem(i, s.isEmpty() ? ItemStack.EMPTY : s);
            return true;
        }
        return false;
    }

    public static boolean removeCount(SimpleContainer inv, Item item, int amount) {
        int remaining = amount;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || s.getItem() != item) continue;
            int take = Math.min(remaining, s.getCount());
            s.shrink(take);
            remaining -= take;
            inv.setItem(i, s.isEmpty() ? ItemStack.EMPTY : s);
            if (remaining <= 0) return true;
        }
        return false;
    }

    public static List<Item> listSaplingVariantsInContainer(SimpleContainer inv) {
        LinkedHashSet<Item> out = new LinkedHashSet<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            Item item = s.getItem();
            if (isSaplingLike(item)) out.add(item);
        }
        return new ArrayList<>(out);
    }

    private static boolean isSaplingLike(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        if (id == null) return false;
        String p = id.getPath();
        return p.endsWith("_sapling") || p.endsWith("_propagule") || p.endsWith("_fungus");
    }

    // ---------------------- Chest scanning / pulling ----------------------

    public static boolean pullFromNearbyContainers(ServerLevel level,
                                                  BlockPos center,
                                                  BlockPos preferred,
                                                  int radius,
                                                  SimpleContainer targetInv,
                                                  Item wanted,
                                                  int maxTake) {
        int remaining = maxTake;

        if (preferred != null) {
            remaining -= pullFromContainerAt(level, preferred, targetInv, wanted, remaining);
            if (remaining <= 0) return true;
        }

        int yMin = center.getY() - 2;
        int yMax = center.getY() + 2;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                for (int y = yMin; y <= yMax; y++) {
                    BlockPos p = new BlockPos(center.getX() + dx, y, center.getZ() + dz);
                    if (preferred != null && preferred.equals(p)) continue;
                    remaining -= pullFromContainerAt(level, p, targetInv, wanted, remaining);
                    if (remaining <= 0) return true;
                }
            }
        }

        return maxTake != remaining;
    }

    private static int pullFromContainerAt(ServerLevel level, BlockPos pos, SimpleContainer targetInv, Item wanted, int maxTake) {
        if (maxTake <= 0) return 0;
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof Container container)) return 0;

        int pulled = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty() || stack.getItem() != wanted) continue;

            int take = Math.min(maxTake - pulled, stack.getCount());
            if (take <= 0) break;

            ItemStack removed = container.removeItem(i, take);
            if (!removed.isEmpty()) {
                giveToContainer(targetInv, removed);
                pulled += removed.getCount();
            }

            if (pulled >= maxTake) break;
        }

        if (pulled > 0) {
            container.setChanged();
        }
        return pulled;
    }

    // ---------------------- Wood logic ----------------------

    public static boolean isWoodDerived(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        if (id == null) return false;
        String p = id.getPath();
        for (String suffix : WOOD_SUFFIXES) {
            if (p.endsWith(suffix)) return true;
        }
        return false;
    }

    public static Item bestLogForWoodDerived(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        if (id == null) return null;
        String base = extractWoodBase(id.getPath());
        if (base == null) return null;
        base = stripKnownPrefixes(base);

        String ns = id.getNamespace();
        for (String suffix : List.of("_log", "_stem", "_wood", "_hyphae")) {
            ResourceLocation key = ResourceLocation.fromNamespaceAndPath(ns, base + suffix);
            if (BuiltInRegistries.ITEM.containsKey(key)) return BuiltInRegistries.ITEM.get(key);
        }
        return null;
    }

    public static boolean tryCraftWoodDerived(SimpleContainer inv, Item wanted) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(wanted);
        if (id == null) return false;

        String path = id.getPath();
        String ns = id.getNamespace();

        String base = extractWoodBase(path);
        if (base == null) return false;
        base = stripKnownPrefixes(base);

        // lookups
        Item planks = itemOrNull(ns, base + "_planks");
        Item log = bestLogForWoodDerived(wanted);

        // planks from logs
        if (path.endsWith("_planks")) {
            if (log == null) return false;
            if (count(inv, log) <= 0) return false;
            if (!removeCount(inv, log, 1)) return false;
            giveToContainer(inv, new ItemStack(wanted, 4));
            return true;
        }

        // ensure planks exist when crafting other wood blocks
        if (planks == null) return false;
        if (count(inv, planks) <= 0) {
            if (log != null && count(inv, log) > 0) {
                // convert one log to planks
                if (removeCount(inv, log, 1)) {
                    giveToContainer(inv, new ItemStack(planks, 4));
                }
            }
        }

        // slab: 3 planks -> 6 slabs
        if (path.endsWith("_slab")) {
            if (count(inv, planks) < 3) return false;
            if (!removeCount(inv, planks, 3)) return false;
            giveToContainer(inv, new ItemStack(wanted, 6));
            return true;
        }

        // stairs: 6 planks -> 4 stairs
        if (path.endsWith("_stairs")) {
            if (count(inv, planks) < 6) return false;
            if (!removeCount(inv, planks, 6)) return false;
            giveToContainer(inv, new ItemStack(wanted, 4));
            return true;
        }

        // fence: 4 planks + 2 sticks -> 3 fences
        if (path.endsWith("_fence")) {
            if (!ensureSticks(inv)) return false;
            if (count(inv, planks) < 4 || count(inv, Items.STICK) < 2) return false;
            if (!removeCount(inv, planks, 4)) return false;
            if (!removeCount(inv, Items.STICK, 2)) return false;
            giveToContainer(inv, new ItemStack(wanted, 3));
            return true;
        }

        // gate: 4 planks + 2 sticks -> 1 gate
        if (path.endsWith("_fence_gate")) {
            if (!ensureSticks(inv)) return false;
            if (count(inv, planks) < 4 || count(inv, Items.STICK) < 2) return false;
            if (!removeCount(inv, planks, 4)) return false;
            if (!removeCount(inv, Items.STICK, 2)) return false;
            giveToContainer(inv, new ItemStack(wanted, 1));
            return true;
        }

        // door: 6 planks -> 3 doors
        if (path.endsWith("_door")) {
            if (count(inv, planks) < 6) return false;
            if (!removeCount(inv, planks, 6)) return false;
            giveToContainer(inv, new ItemStack(wanted, 3));
            return true;
        }

        // trapdoor: 6 planks -> 2 trapdoors
        if (path.endsWith("_trapdoor")) {
            if (count(inv, planks) < 6) return false;
            if (!removeCount(inv, planks, 6)) return false;
            giveToContainer(inv, new ItemStack(wanted, 2));
            return true;
        }

        return false;
    }

    private static boolean ensureSticks(SimpleContainer inv) {
        if (count(inv, Items.STICK) >= 2) return true;
        // craft sticks from any planks present
        // find any *planks in inventory and convert 2 -> 4 sticks
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            Item it = s.getItem();
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(it);
            if (id == null) continue;
            if (!id.getPath().endsWith("_planks")) continue;
            if (count(inv, it) < 2) continue;
            if (!removeCount(inv, it, 2)) continue;
            giveToContainer(inv, new ItemStack(Items.STICK, 4));
            return true;
        }
        return count(inv, Items.STICK) >= 2;
    }

    private static Item itemOrNull(String namespace, String path) {
        ResourceLocation key = ResourceLocation.fromNamespaceAndPath(namespace, path);
        if (!BuiltInRegistries.ITEM.containsKey(key)) return null;
        Item it = BuiltInRegistries.ITEM.get(key);
        return (it == null || it == Items.AIR) ? null : it;
    }

    // ---------------------- Logs / saplings in world ----------------------

    public static boolean hasAnyLogsNearby(ServerLevel level, BlockPos center, int radius) {
        int yMin = center.getY() - 4;
        int yMax = center.getY() + 4;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                for (int y = yMin; y <= yMax; y++) {
                    BlockPos p = new BlockPos(center.getX() + dx, y, center.getZ() + dz);
                    if (level.getBlockState(p).is(BlockTags.LOGS)) return true;
                }
            }
        }
        return false;
    }

    public static boolean isLogLike(BlockState state) {
        return state.is(BlockTags.LOGS);
    }

    public static boolean isLogBlock(BlockState state) {
        return isLogLike(state);
    }



    public static Item saplingForLogBlock(BlockState logState) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(logState.getBlock());
        if (id == null) return null;

        String base = extractWoodBase(id.getPath());
        if (base == null) {
            // handle *_stem / *_hyphae by manual stripping
            if (id.getPath().endsWith("_stem")) base = id.getPath().substring(0, id.getPath().length() - "_stem".length());
            else if (id.getPath().endsWith("_hyphae")) base = id.getPath().substring(0, id.getPath().length() - "_hyphae".length());
            else return null;
        }
        base = stripKnownPrefixes(base);

        for (String suf : SAPLING_SUFFIXES) {
            ResourceLocation cand = ResourceLocation.fromNamespaceAndPath(id.getNamespace(), base + suf);
            if (BuiltInRegistries.ITEM.containsKey(cand)) {
                Item it = BuiltInRegistries.ITEM.get(cand);
                return (it == null || it == Items.AIR) ? null : it;
            }
        }
        return null;
    }

    public static boolean placeSaplingBlock(ServerLevel level, BlockPos pos, Item saplingItem) {
        if (!(saplingItem instanceof BlockItem bi)) return false;
        Block sapBlock = bi.getBlock();

        BlockState place = sapBlock.defaultBlockState();
        if (!level.isEmptyBlock(pos)) return false;
        if (!place.canSurvive(level, pos)) return false;

        level.setBlockAndUpdate(pos, place);
        return true;
    }

    public static void tryAdvanceSapling(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        Block b = state.getBlock();
        if (!(b instanceof SaplingBlock sapling)) return;

        // advanceTree is protected in vanilla; call reflectively.
        try {
            RandomSource rnd = levelRandom(level);
            Method m = null;
            try {
                m = SaplingBlock.class.getDeclaredMethod("advanceTree", ServerLevel.class, BlockPos.class, BlockState.class, RandomSource.class);
            } catch (NoSuchMethodException ignored) {
                // try fallback lookup by name
                for (Method mm : SaplingBlock.class.getDeclaredMethods()) {
                    if (!mm.getName().equals("advanceTree")) continue;
                    Class<?>[] p = mm.getParameterTypes();
                    if (p.length == 4 && p[0] == ServerLevel.class && p[1] == BlockPos.class && p[2] == BlockState.class) {
                        m = mm;
                        break;
                    }
                }
            }
            if (m == null) return;
            m.setAccessible(true);
            // If the last param isn't RandomSource (mappings variance), try passing our rnd anyway.
            m.invoke(sapling, level, pos, state, rnd);
        } catch (Throwable ignored) {
            // best-effort
        }
    }

    private static RandomSource levelRandom(Level level) {
        // Prefer the world's random source if we can get it; otherwise create a temporary one.
        try {
            Field f = Level.class.getDeclaredField("random");
            f.setAccessible(true);
            Object o = f.get(level);
            if (o instanceof RandomSource rs) return rs;
        } catch (Throwable ignored) {
        }
        try {
            Method m = Level.class.getMethod("getRandom");
            Object o = m.invoke(level);
            if (o instanceof RandomSource rs) return rs;
        } catch (Throwable ignored) {
        }
        return RandomSource.create();
    }

    public static void tryAdvanceSaplingsNear(ServerLevel level, BlockPos center, int radius) {
        int yMin = center.getY() - 1;
        int yMax = center.getY() + 2;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                for (int y = yMin; y <= yMax; y++) {
                    BlockPos p = new BlockPos(center.getX() + dx, y, center.getZ() + dz);
                    BlockState st = level.getBlockState(p);
                    if (st.getBlock() instanceof SaplingBlock) {
                        tryAdvanceSapling(level, p);
                    }


    public static boolean hasAnyLogNearby(ServerLevel level, BlockPos center, int radius) {
        if (radius <= 0) return false;
        int cx = center.getX();
        int cy = center.getY();
        int cz = center.getZ();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -2; dy <= 4; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos p = new BlockPos(cx + dx, cy + dy, cz + dz);
                    // Never force-load chunks (avoids server hangs during worldgen/chunk loads)
                    if (!level.hasChunkAt(p)) continue;

                    BlockState st = level.getBlockState(p);
                    if (isLogBlock(st)) return true;
                }
            }
        }
        return false;
    }

                }
            }
        }
    }

    // ---------------------- Sapling planting helpers ----------------------

    public static void plantSaplingsInRing(ServerLevel level, BlockPos center, SimpleContainer inv, int perVariant) {
        List<Item> variants = listSaplingVariantsInContainer(inv);
        if (variants.isEmpty()) return;
        plantSpecificSaplingsInRing(level, center, inv, new LinkedHashSet<>(variants), perVariant);
    }

    public static void plantSpecificSaplingsInRing(ServerLevel level, BlockPos center, SimpleContainer inv, Set<Item> saplings, int perType) {
        if (saplings == null || saplings.isEmpty() || perType <= 0) return;

        List<BlockPos> ring = ringPositions(center, 2);
        int ringIdx = 0;

        for (Item sapling : saplings) {
            int planted = 0;
            while (planted < perType) {
                if (!removeOne(inv, sapling)) break;

                boolean placed = false;
                for (int attempts = 0; attempts < ring.size(); attempts++) {
                    BlockPos p = ring.get(ringIdx++ % ring.size());
                    if (!level.isEmptyBlock(p)) continue;
                    if (!level.getFluidState(p).isEmpty()) continue;
                    if (!level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)) continue;

                    if (placeSaplingBlock(level, p, sapling)) {
                        placed = true;
                        break;
                    }
                }

                if (!placed) {
                    // give it back if we couldn't place
                    giveToContainer(inv, new ItemStack(sapling, 1));
                    break;
                }

                planted++;
            }
        }
    }

    private static List<BlockPos> ringPositions(BlockPos center, int r) {
        List<BlockPos> out = new ArrayList<>();
        int cx = center.getX();
        int cy = center.getY();
        int cz = center.getZ();

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (Math.abs(dx) != r && Math.abs(dz) != r) continue; // ring only
                out.add(new BlockPos(cx + dx, cy, cz + dz));
            }
        }
        Collections.shuffle(out);
        return out;
    }

    // ---------------------- String / ID parsing ----------------------

    private static String extractWoodBase(String path) {
        for (String suffix : WOOD_SUFFIXES) {
            if (path.endsWith(suffix)) {
                return path.substring(0, path.length() - suffix.length());
            }
        }
        return null;
    }

    private static String stripKnownPrefixes(String base) {
        if (base.startsWith("stripped_")) return base.substring("stripped_".length());
        return base;
    }
}

    public static void tryReplantAfterLogBreak(ServerLevel level, Entity roamer, BlockPos brokenPos, BlockState brokenState) {
        if (level == null || roamer == null || brokenPos == null || brokenState == null) return;

        long now = level.getGameTime();
        Long last = LAST_REPLANT_TICK.get(roamer.getUUID());
        if (last != null && (now - last) < 40L) return; // 2 seconds

        Item sapling = saplingForLogBlock(brokenState);
        if (sapling == null || sapling == Items.AIR) return;

        Object invObj = invoke(roamer, "getInventory");
        if (!(invObj instanceof SimpleContainer inv)) return;

        if (!containerHas(inv, sapling)) return;

        // Prefer the base area of the tree, but don't force-load chunks.
        BlockPos base = brokenPos;
        for (int i = 0; i < 12; i++) {
            BlockPos below = base.below();
            if (!level.hasChunkAt(below)) break;
            if (!isLogBlock(level.getBlockState(below))) break;
            base = below;
        }

        // Try a few nearby spots at the same Y as the base log (air above ground).
        List<BlockPos> candidates = new ArrayList<>();
        candidates.add(base);
        candidates.addAll(ringPositions(base, 1));
        Collections.shuffle(candidates);

        for (BlockPos p : candidates) {
            if (!level.hasChunkAt(p) || !level.hasChunkAt(p.below())) continue;
            if (!level.isEmptyBlock(p)) continue;
            if (!level.getFluidState(p).isEmpty()) continue;
            if (!level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)) continue;

            if (!removeOne(inv, sapling)) return;

            boolean placed = placeSaplingBlock(level, p, sapling);
            if (!placed) {
                // give it back if we couldn't place
                giveToContainer(inv, new ItemStack(sapling, 1));
                return;
            }

            LAST_REPLANT_TICK.put(roamer.getUUID(), now);
            return;
        }
    }



    public static Item saplingForWantedItem(Item wanted) {
        if (wanted == null) return null;
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(wanted);
        if (id == null) return null;
        String path = id.getPath();

        // Common vanilla wood prefixes
        String[] woods = new String[]{
                "oak", "birch", "spruce", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "bamboo"
        };

        for (String w : woods) {
            if (path.startsWith(w + "_") || path.contains("_" + w + "_") || path.endsWith("_" + w)) {
                ResourceLocation sap = ResourceLocation.fromNamespaceAndPath("minecraft", w + "_sapling");
                if (BuiltInRegistries.ITEM.containsKey(sap)) return BuiltInRegistries.ITEM.get(sap);
            }
        }

        return null;
    }



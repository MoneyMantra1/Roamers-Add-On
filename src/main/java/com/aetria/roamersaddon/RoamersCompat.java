package com.aetria.roamersaddon;

import com.aetria.roamersaddon.RoamersAddonEvents.WantedInfo;
import com.aetria.roamersaddon.RoamersAddonEvents.WantedType;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

import static com.aetria.roamersaddon.RoamersAddonMod.LOGGER;

public final class RoamersCompat {

    private static final ResourceLocation ROAMER_ENTITY_ID = ResourceLocation.fromNamespaceAndPath("roamers", "roamer");

    // Suffixes we consider to represent a "wood set" (used to infer saplings)
    private static final List<String> WOOD_SUFFIXES = List.of(
            "_log", "_wood",
            "_planks",
            "_stairs", "_slab",
            "_fence", "_fence_gate",
            "_door", "_trapdoor",
            "_pressure_plate", "_button",
            "_sign", "_wall_sign",
            "_hanging_sign", "_wall_hanging_sign"
    );

    private static final List<String> SAPLING_SUFFIXES = List.of(
            "_sapling",
            "_propagule", // mangrove
            "_fungus"     // nether "saplings"
    );

    private RoamersCompat() {}

    public static boolean isRoamerEntity(Entity e) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
        return ROAMER_ENTITY_ID.equals(id);
    }

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
                if (args[i] == null) continue; // can't type-check null
                if (!wrap(p[i]).isAssignableFrom(wrap(args[i].getClass()))) continue outer;
            }
            return m;
        }
        // Also try declared methods
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

    public static Set<Item> findSaplingsForRoamerStructures(Entity roamer) {
        try {
            Object land = invoke(roamer, "getLand");
            if (land == null) return Set.of();

            Object buildingsObj = invoke(land, "getBuildings");
            if (!(buildingsObj instanceof Map<?, ?> buildings)) return Set.of();

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

                    // try a few sapling naming conventions in same namespace
                    for (String suffix : SAPLING_SUFFIXES) {
                        ResourceLocation candidate = ResourceLocation.fromNamespaceAndPath(blockId.getNamespace(), base + suffix);
                        if (BuiltInRegistries.ITEM.containsKey(candidate)) {
                            saplingIds.add(candidate);
                            break; // pick the first that exists
                        }
                    }
                }
            }

            if (saplingIds.isEmpty()) return Set.of();

            Set<Item> out = new LinkedHashSet<>();
            for (ResourceLocation id : saplingIds) {
                Item item = BuiltInRegistries.ITEM.get(id);
                if (item != null) out.add(item);
            }
            return out;
        } catch (Throwable t) {
            LOGGER.debug("Sapling inference failed: {}", t.toString());
            return Set.of();
        }
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

            // turn enum-like names into friendly verbs
            if (raw.contains("build")) return "building";
            if (raw.contains("mine") || raw.contains("excavat")) return "excavating";
            if (raw.contains("craft")) return "crafting";
            if (raw.contains("sleep")) return "resting";
            if (raw.contains("wander")) return "wandering";
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
                if (asItem != null && asItem != net.minecraft.world.item.Items.AIR) {
                    ResourceLocation id = BuiltInRegistries.ITEM.getKey(asItem);
                    String key = "build:" + (id == null ? asItem.toString() : id.toString());
                    return new WantedInfo(WantedType.BUILD, asItem, key);
                }
            }

            Object wantedItemObj = invoke(roamer, "getWantedCraftingItem");
            if (wantedItemObj instanceof Item item) {
                if (item != net.minecraft.world.item.Items.AIR) {
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

    private static String extractWoodBase(String path) {
        for (String suffix : WOOD_SUFFIXES) {
            if (path.endsWith(suffix)) {
                return path.substring(0, path.length() - suffix.length());
            }
        }
        return null;
    }

    private static String stripKnownPrefixes(String base) {
        // Roamers structures may use stripped logs etc.
        if (base.startsWith("stripped_")) return base.substring("stripped_".length());
        return base;
    }

    public static boolean containerHas(SimpleContainer inv, Item item) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.getItem() == item) return true;
        }
        return false;
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

        // If no room, drop on ground near entity? We don't know position here; ignore.
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
        if (item instanceof BlockItem bi) {
            Block b = bi.getBlock();
            if (b instanceof SaplingBlock) return true;

            // heuristic based on item id path
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (id != null) {
                String p = id.getPath();
                return p.endsWith("_sapling") || p.endsWith("_propagule") || p.endsWith("_fungus");
            }
        } else {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (id != null) {
                String p = id.getPath();
                return p.endsWith("_sapling") || p.endsWith("_propagule") || p.endsWith("_fungus");
            }
        }
        return false;
    }
}

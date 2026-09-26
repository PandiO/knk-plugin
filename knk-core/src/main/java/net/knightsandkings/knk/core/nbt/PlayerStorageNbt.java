package net.knightsandkings.knk.core.nbt;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import net.knightsandkings.knk.core.nbt.NbtTag.ByteTag;
import net.knightsandkings.knk.core.nbt.NbtTag.CompoundTag;
import net.knightsandkings.knk.core.nbt.NbtTag.IntTag;
import net.knightsandkings.knk.core.nbt.NbtTag.ListTag;

/**
 * The storage parts of a player data file ({@code playerdata/<uuid>.dat}) - KNG-13, offline
 * {@code /inventory} and {@code /enderchest}. Items stay raw NBT compounds here ({@code id}, {@code count},
 * {@code components}, ...); knk-paper turns them into ItemStacks with Paper's
 * {@code ItemStack.deserializeBytes}, which also upgrades items saved by an older version.
 * <ul>
 *   <li>{@code Inventory}: list of items with a {@code Slot} byte; 0-8 hotbar, 9-35 the rest.</li>
 *   <li>{@code equipment} (1.21.5+): compound keyed {@code head/chest/legs/feet/offhand} (plus
 *       {@code body}/{@code saddle}, kept untouched). Files from before 1.21.5 kept armour and off-hand
 *       in {@code Inventory} as slots 100-103 and -106; those are read too.</li>
 *   <li>{@code EnderItems}: list of items with a {@code Slot} byte, 0-26.</li>
 * </ul>
 * Writing always uses the 1.21.5+ layout, so only write a file whose {@code DataVersion} is the
 * running server's (knk-paper refuses otherwise).
 */
public final class PlayerStorageNbt {

    public static final int MAIN_INVENTORY_SIZE = 36;
    public static final int ENDER_CHEST_SIZE = 27;

    public static final String DATA_VERSION = "DataVersion";
    private static final String INVENTORY = "Inventory";
    private static final String EQUIPMENT = "equipment";
    private static final String ENDER_ITEMS = "EnderItems";
    private static final String SLOT = "Slot";

    /** Armour and off-hand, with their 1.21.5+ equipment key and their pre-1.21.5 Inventory slot. */
    public enum Equipment {
        HEAD("head", 103),
        CHEST("chest", 102),
        LEGS("legs", 101),
        FEET("feet", 100),
        OFFHAND("offhand", -106);

        private final String key;
        private final int legacySlot;

        Equipment(String key, int legacySlot) {
            this.key = key;
            this.legacySlot = legacySlot;
        }

        public String key() {
            return key;
        }

        static Optional<Equipment> byLegacySlot(int slot) {
            for (Equipment equipment : values()) {
                if (equipment.legacySlot == slot) {
                    return Optional.of(equipment);
                }
            }
            return Optional.empty();
        }
    }

    private PlayerStorageNbt() {
    }

    public static int dataVersion(CompoundTag root) {
        return root.getInt(DATA_VERSION).orElse(0);
    }

    /** Main inventory items by slot (0-35), without their {@code Slot} key. */
    public static Map<Integer, CompoundTag> inventory(CompoundTag root) {
        Map<Integer, CompoundTag> items = new TreeMap<>();
        slottedItems(root, INVENTORY).forEach((slot, item) -> {
            if (slot >= 0 && slot < MAIN_INVENTORY_SIZE) {
                items.put(slot, item);
            }
        });
        return items;
    }

    /** Armour and off-hand, from the {@code equipment} compound or, in older files, the legacy slots. */
    public static Map<Equipment, CompoundTag> equipment(CompoundTag root) {
        Map<Equipment, CompoundTag> items = new EnumMap<>(Equipment.class);
        slottedItems(root, INVENTORY).forEach((slot, item) ->
                Equipment.byLegacySlot(slot).ifPresent(equipment -> items.put(equipment, item)));
        root.getCompound(EQUIPMENT).ifPresent(equipment -> {
            for (Equipment slot : Equipment.values()) {
                equipment.getCompound(slot.key()).ifPresent(item -> items.put(slot, item.deepCopy()));
            }
        });
        return items;
    }

    /** Ender chest items by slot (0-26), without their {@code Slot} key. */
    public static Map<Integer, CompoundTag> enderChest(CompoundTag root) {
        Map<Integer, CompoundTag> items = new TreeMap<>();
        slottedItems(root, ENDER_ITEMS).forEach((slot, item) -> {
            if (slot >= 0 && slot < ENDER_CHEST_SIZE) {
                items.put(slot, item);
            }
        });
        return items;
    }

    /**
     * Replaces the main inventory and the armour/off-hand items. Items in other slots of the
     * {@code Inventory} list and other {@code equipment} entries (body, saddle) are kept; legacy
     * armour slots are dropped since the armour now goes into {@code equipment}.
     */
    public static void setInventory(CompoundTag root, Map<Integer, CompoundTag> main, Map<Equipment, CompoundTag> equipment) {
        List<CompoundTag> list = new ArrayList<>();
        slottedItems(root, INVENTORY).forEach((slot, item) -> {
            boolean replaced = (slot >= 0 && slot < MAIN_INVENTORY_SIZE) || Equipment.byLegacySlot(slot).isPresent();
            if (!replaced) {
                list.add(withSlot(item, slot));
            }
        });
        new TreeMap<>(main).forEach((slot, item) -> {
            if (slot < 0 || slot >= MAIN_INVENTORY_SIZE) {
                throw new IllegalArgumentException("Inventory slot out of range: " + slot);
            }
            list.add(withSlot(item, slot));
        });
        root.put(INVENTORY, ListTag.of((byte) 10, list));

        CompoundTag equipmentTag = root.getCompound(EQUIPMENT).orElseGet(CompoundTag::new);
        for (Equipment slot : Equipment.values()) {
            CompoundTag item = equipment.get(slot);
            if (item == null) {
                equipmentTag.remove(slot.key());
            } else {
                equipmentTag.put(slot.key(), stripSlot(item));
            }
        }
        if (equipmentTag.values().isEmpty()) {
            root.remove(EQUIPMENT);
        } else {
            root.put(EQUIPMENT, equipmentTag);
        }
    }

    /** Replaces the ender chest contents. */
    public static void setEnderChest(CompoundTag root, Map<Integer, CompoundTag> items) {
        List<CompoundTag> list = new ArrayList<>();
        new TreeMap<>(items).forEach((slot, item) -> {
            if (slot < 0 || slot >= ENDER_CHEST_SIZE) {
                throw new IllegalArgumentException("Ender chest slot out of range: " + slot);
            }
            list.add(withSlot(item, slot));
        });
        root.put(ENDER_ITEMS, ListTag.of((byte) 10, list));
    }

    /** An item from the file, shaped for Paper's {@code ItemStack.deserializeBytes}: the file's data version added. */
    public static CompoundTag forDeserialize(CompoundTag item, int dataVersion) {
        CompoundTag copy = stripSlot(item);
        copy.put(DATA_VERSION, new IntTag(dataVersion));
        return copy;
    }

    /** An item from Paper's {@code ItemStack.serializeAsBytes}, shaped for the file: its data version removed. */
    public static CompoundTag fromSerialized(CompoundTag serialized) {
        CompoundTag copy = serialized.deepCopy();
        copy.remove(DATA_VERSION);
        return copy;
    }

    private static Map<Integer, CompoundTag> slottedItems(CompoundTag root, String listKey) {
        Map<Integer, CompoundTag> items = new TreeMap<>();
        root.getList(listKey).ifPresent(list -> {
            for (NbtTag tag : list.values()) {
                if (tag instanceof CompoundTag item) {
                    item.getInt(SLOT).ifPresent(slot -> items.put(slot, stripSlot(item)));
                }
            }
        });
        return items;
    }

    private static CompoundTag stripSlot(CompoundTag item) {
        CompoundTag copy = item.deepCopy();
        copy.remove(SLOT);
        return copy;
    }

    private static CompoundTag withSlot(CompoundTag item, int slot) {
        CompoundTag copy = new CompoundTag();
        copy.put(SLOT, new ByteTag((byte) slot));
        stripSlot(item).values().forEach(copy::put);
        return copy;
    }
}

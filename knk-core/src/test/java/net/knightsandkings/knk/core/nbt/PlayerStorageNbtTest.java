package net.knightsandkings.knk.core.nbt;

import net.knightsandkings.knk.core.nbt.NbtTag.*;
import net.knightsandkings.knk.core.nbt.PlayerStorageNbt.Equipment;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** KNG-13: reading and writing the storage parts of playerdata/<uuid>.dat. */
class PlayerStorageNbtTest {

    private static CompoundTag item(String id, int count) {
        return new CompoundTag().put("id", new StringTag("minecraft:" + id)).put("count", new IntTag(count));
    }

    private static CompoundTag slotted(int slot, String id) {
        CompoundTag item = new CompoundTag().put("Slot", new ByteTag((byte) slot));
        item(id, 1).values().forEach(item::put);
        return item;
    }

    /** A 1.21.5+ player file: armour and off-hand in "equipment". */
    private static CompoundTag modernFile() {
        return new CompoundTag()
                .put("DataVersion", new IntTag(4556))
                .put("Health", new FloatTag(20f))
                .put("Inventory", ListTag.of((byte) 10, List.of(slotted(0, "diamond_sword"), slotted(35, "torch"))))
                .put("equipment", new CompoundTag()
                        .put("head", item("iron_helmet", 1))
                        .put("offhand", item("shield", 1))
                        .put("body", item("saddle_thing", 1)))
                .put("EnderItems", ListTag.of((byte) 10, List.of(slotted(26, "emerald"))));
    }

    @Test
    void readsAModernFile() {
        CompoundTag root = modernFile();

        assertEquals(4556, PlayerStorageNbt.dataVersion(root));
        Map<Integer, CompoundTag> inventory = PlayerStorageNbt.inventory(root);
        assertEquals(List.of(0, 35), List.copyOf(inventory.keySet()));
        assertEquals(item("diamond_sword", 1), inventory.get(0)); // Slot stripped
        Map<Equipment, CompoundTag> equipment = PlayerStorageNbt.equipment(root);
        assertEquals(item("iron_helmet", 1), equipment.get(Equipment.HEAD));
        assertEquals(item("shield", 1), equipment.get(Equipment.OFFHAND));
        assertEquals(2, equipment.size()); // body isn't player armour
        assertEquals(item("emerald", 1), PlayerStorageNbt.enderChest(root).get(26));
    }

    @Test
    void readsAPre1215FileWithArmourInTheInventoryList() {
        CompoundTag root = new CompoundTag()
                .put("DataVersion", new IntTag(3953))
                .put("Inventory", ListTag.of((byte) 10, List.of(
                        slotted(3, "bread"), slotted(103, "iron_helmet"), slotted(100, "iron_boots"), slotted(-106, "shield"))));

        assertEquals(List.of(3), List.copyOf(PlayerStorageNbt.inventory(root).keySet()));
        Map<Equipment, CompoundTag> equipment = PlayerStorageNbt.equipment(root);
        assertEquals(item("iron_helmet", 1), equipment.get(Equipment.HEAD));
        assertEquals(item("iron_boots", 1), equipment.get(Equipment.FEET));
        assertEquals(item("shield", 1), equipment.get(Equipment.OFFHAND));
    }

    @Test
    void settingTheInventoryKeepsEverythingElse() {
        CompoundTag root = modernFile();
        Map<Integer, CompoundTag> main = new TreeMap<>(Map.of(5, item("bread", 3)));
        Map<Equipment, CompoundTag> equipment = new EnumMap<>(Map.of(Equipment.CHEST, item("iron_chestplate", 1)));

        PlayerStorageNbt.setInventory(root, main, equipment);

        assertEquals(Map.of(5, item("bread", 3)), PlayerStorageNbt.inventory(root));
        assertEquals(Map.of(Equipment.CHEST, item("iron_chestplate", 1)), PlayerStorageNbt.equipment(root));
        CompoundTag equipmentTag = root.getCompound("equipment").orElseThrow();
        assertEquals(item("saddle_thing", 1), equipmentTag.getCompound("body").orElseThrow()); // untouched
        assertFalse(equipmentTag.contains("head"));
        assertEquals(new FloatTag(20f), root.get("Health").orElseThrow());
        assertEquals(1, PlayerStorageNbt.enderChest(root).size());
        CompoundTag written = (CompoundTag) root.getList("Inventory").orElseThrow().values().get(0);
        assertEquals(List.of("Slot", "id", "count"), List.copyOf(written.values().keySet()));
    }

    @Test
    void clearingEverythingRemovesAnEmptyEquipmentCompound() {
        CompoundTag root = new CompoundTag().put("equipment", new CompoundTag().put("head", item("iron_helmet", 1)));

        PlayerStorageNbt.setInventory(root, Map.of(), Map.of());

        assertFalse(root.contains("equipment"));
        assertTrue(root.getList("Inventory").orElseThrow().values().isEmpty());
    }

    @Test
    void writingMovesLegacyArmourIntoEquipment() {
        CompoundTag root = new CompoundTag().put("Inventory", ListTag.of((byte) 10, List.of(slotted(103, "iron_helmet"))));

        PlayerStorageNbt.setInventory(root, Map.of(), PlayerStorageNbt.equipment(root));

        assertTrue(root.getList("Inventory").orElseThrow().values().isEmpty());
        assertEquals(item("iron_helmet", 1), root.getCompound("equipment").orElseThrow().getCompound("head").orElseThrow());
    }

    @Test
    void settingTheEnderChest() {
        CompoundTag root = modernFile();

        PlayerStorageNbt.setEnderChest(root, Map.of(0, item("diamond", 64), 13, item("apple", 2)));

        assertEquals(Map.of(0, item("diamond", 64), 13, item("apple", 2)), PlayerStorageNbt.enderChest(root));
        assertThrows(IllegalArgumentException.class, () -> PlayerStorageNbt.setEnderChest(root, Map.of(27, item("apple", 1))));
    }

    @Test
    void dataVersionShapingForPaperItemSerialization() {
        CompoundTag fromFile = slotted(4, "bread");

        CompoundTag forPaper = PlayerStorageNbt.forDeserialize(fromFile, 4556);
        assertEquals(4556, forPaper.getInt("DataVersion").orElseThrow());
        assertFalse(forPaper.contains("Slot"));
        assertTrue(fromFile.contains("Slot")); // input untouched

        assertEquals(item("bread", 1), PlayerStorageNbt.fromSerialized(forPaper));
    }
}

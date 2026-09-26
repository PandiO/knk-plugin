package net.knightsandkings.knk.paper.inventory;

import net.knightsandkings.knk.core.nbt.NbtTag.CompoundTag;
import net.knightsandkings.knk.core.nbt.NbtTag.StringTag;
import net.knightsandkings.knk.core.nbt.PlayerStorageNbt.Equipment;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** KNG-13: the offline inventory view mirrors the player's own screen and maps back losslessly. */
class OfflineStorageViewsLayoutTest {

    private static CompoundTag item(String id) {
        return new CompoundTag().put("id", new StringTag(id));
    }

    @Test
    void hotbarGoesToRowFourAndEquipmentToRowFive() {
        Map<Integer, CompoundTag> main = Map.of(0, item("hotbar0"), 8, item("hotbar8"), 9, item("top-left"), 35, item("last"));
        Map<Equipment, CompoundTag> equipment = Map.of(Equipment.HEAD, item("helmet"), Equipment.OFFHAND, item("shield"));

        Map<Integer, CompoundTag> view = OfflineStorageViews.toViewSlots(main, equipment);

        assertEquals(item("top-left"), view.get(0));
        assertEquals(item("last"), view.get(26));
        assertEquals(item("hotbar0"), view.get(27));
        assertEquals(item("hotbar8"), view.get(35));
        assertEquals(item("helmet"), view.get(36));
        assertEquals(item("shield"), view.get(40));
    }

    @Test
    void everySlotMapsBack() {
        Map<Integer, CompoundTag> main = new HashMap<>();
        for (int slot = 0; slot < 36; slot++) {
            main.put(slot, item("slot" + slot));
        }
        Map<Equipment, CompoundTag> equipment = new EnumMap<>(Equipment.class);
        for (Equipment slot : Equipment.values()) {
            equipment.put(slot, item(slot.key()));
        }

        Map<Integer, CompoundTag> mainBack = new TreeMap<>();
        Map<Equipment, CompoundTag> equipmentBack = new EnumMap<>(Equipment.class);
        OfflineStorageViews.fromViewSlots(OfflineStorageViews.toViewSlots(main, equipment), mainBack, equipmentBack);

        assertEquals(main, mainBack);
        assertEquals(equipment, equipmentBack);
    }

    @Test
    void theLastFourSlotsAreLockedFiller() {
        assertFalse(OfflineStorageViews.isFiller(40));
        assertTrue(OfflineStorageViews.isFiller(41));
        assertTrue(OfflineStorageViews.isFiller(44));
        assertFalse(OfflineStorageViews.isFiller(45)); // the viewer's own inventory
    }
}

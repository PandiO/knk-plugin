package net.knightsandkings.knk.paper.inventory;

import net.knightsandkings.knk.core.nbt.NbtTag.CompoundTag;
import net.knightsandkings.knk.core.nbt.NbtTag.StringTag;
import net.knightsandkings.knk.core.nbt.PlayerStorageNbt.Equipment;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    void theLastFourSlotsAreLocked() {
        assertFalse(OfflineStorageViews.isLocked(40));
        assertTrue(OfflineStorageViews.isLocked(41));
        assertTrue(OfflineStorageViews.isLocked(44));
        assertFalse(OfflineStorageViews.isLocked(45)); // the viewer's own inventory
    }

    /** A stand-in ItemStack (a real one needs a server's item registry): kind, amount, max stack. */
    private static ItemStack stack(String kind, int amount, int max) {
        ItemStack item = mock(ItemStack.class);
        int[] count = {amount};
        when(item.getAmount()).thenAnswer(inv -> count[0]);
        doAnswer(inv -> {
            count[0] = inv.getArgument(0);
            return null;
        }).when(item).setAmount(anyInt());
        when(item.getMaxStackSize()).thenReturn(max);
        when(item.isEmpty()).thenAnswer(inv -> count[0] <= 0);
        when(item.toString()).thenReturn(kind);
        when(item.isSimilar(any())).thenAnswer(inv -> inv.getArgument(0) != null && kind.equals(inv.getArgument(0).toString()));
        when(item.clone()).thenAnswer(inv -> stack(kind, count[0], max));
        return item;
    }

    @Test
    void shiftClickFillsMatchingStacksThenEmptySlotsButNeverTheLockedOnes() {
        ItemStack[] contents = new ItemStack[45];
        for (int slot = 0; slot < 41; slot++) {
            contents[slot] = stack("dirt", 64, 64); // full
        }
        contents[3] = stack("bread", 60, 64);
        contents[7] = null;

        int left = OfflineStorageViews.moveInto(contents, 41, stack("bread", 20, 64));

        assertEquals(64, contents[3].getAmount());
        assertEquals("bread", contents[7].toString());
        assertEquals(16, contents[7].getAmount());
        assertEquals(0, left);
        for (int slot = 41; slot < 45; slot++) {
            assertNull(contents[slot]);
        }
    }

    @Test
    void whatDoesNotFitStaysWithTheViewer() {
        ItemStack[] contents = new ItemStack[45];
        for (int slot = 0; slot < 41; slot++) {
            contents[slot] = stack("dirt", 64, 64);
        }

        assertEquals(10, OfflineStorageViews.moveInto(contents, 41, stack("bread", 10, 64)));
        for (int slot = 41; slot < 45; slot++) {
            assertNull(contents[slot]);
        }
    }
}

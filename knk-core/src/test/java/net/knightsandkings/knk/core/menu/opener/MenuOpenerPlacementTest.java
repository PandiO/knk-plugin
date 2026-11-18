package net.knightsandkings.knk.core.menu.opener;

import net.knightsandkings.knk.core.menu.opener.MenuOpenerPlacement.Plan;
import net.knightsandkings.knk.core.menu.opener.MenuOpenerPlacement.Slot;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PandiO/knk-workspace#160: where the menu item goes, and that nothing of the player's is lost. */
class MenuOpenerPlacementTest {

    private static Slot[] inventory() {
        Slot[] slots = new Slot[41];
        Arrays.fill(slots, Slot.EMPTY);
        return slots;
    }

    @Test
    void anEmptyConfiguredSlotGetsTheItem() {
        Plan plan = MenuOpenerPlacement.plan(inventory(), true, 8, true);
        assertEquals(8, plan.place());
        assertEquals(-1, plan.moveFrom());
        assertTrue(plan.remove().isEmpty());
    }

    @Test
    void thePlayersItemInTheSlotMovesToTheMainInventoryFirst() {
        Slot[] slots = inventory();
        slots[8] = Slot.OTHER;
        slots[0] = Slot.EMPTY;
        slots[9] = Slot.OTHER;
        Plan plan = MenuOpenerPlacement.plan(slots, true, 8, true);
        assertEquals(8, plan.moveFrom());
        assertEquals(10, plan.moveTo());
        assertEquals(8, plan.place());
    }

    @Test
    void theHotbarIsUsedWhenTheMainInventoryIsFull() {
        Slot[] slots = inventory();
        Arrays.fill(slots, 0, 36, Slot.OTHER);
        slots[3] = Slot.EMPTY;
        Plan plan = MenuOpenerPlacement.plan(slots, true, 8, true);
        assertEquals(8, plan.moveFrom());
        assertEquals(3, plan.moveTo());
    }

    @Test
    void aFullInventoryGetsNothingAndLosesNothing() {
        Slot[] slots = inventory();
        Arrays.fill(slots, 0, 36, Slot.OTHER);
        Plan plan = MenuOpenerPlacement.plan(slots, true, 8, true);
        assertTrue(plan.noRoom());
        assertTrue(plan.changesNothing());
    }

    @Test
    void anItemAlreadyThereStaysAndCopiesGo() {
        Slot[] slots = inventory();
        slots[8] = Slot.OPENER;
        slots[20] = Slot.OPENER;
        slots[40] = Slot.OPENER;
        Plan plan = MenuOpenerPlacement.plan(slots, true, 8, true);
        assertEquals(List.of(20, 40), plan.remove());
        assertEquals(-1, plan.place());
        assertEquals(-1, plan.refresh());
    }

    @Test
    void anOutdatedItemIsReplacedInPlace() {
        Slot[] slots = inventory();
        slots[8] = Slot.OPENER_OUTDATED;
        Plan plan = MenuOpenerPlacement.plan(slots, true, 8, true);
        assertEquals(8, plan.refresh());
        assertTrue(plan.remove().isEmpty());
    }

    @Test
    void aMovableItemMayStayWhereThePlayerPutIt() {
        Slot[] slots = inventory();
        slots[15] = Slot.OPENER;
        Plan plan = MenuOpenerPlacement.plan(slots, true, 8, false);
        assertTrue(plan.changesNothing());
    }

    @Test
    void aPinnedItemInAnotherSlotGoesBackToTheConfiguredOne() {
        Slot[] slots = inventory();
        slots[15] = Slot.OPENER;
        slots[8] = Slot.OTHER;
        Plan plan = MenuOpenerPlacement.plan(slots, true, 8, true);
        assertEquals(List.of(15), plan.remove());
        assertEquals(8, plan.moveFrom());
        assertEquals(9, plan.moveTo());
        assertEquals(8, plan.place());
    }

    @Test
    void notWantedRemovesEveryCopy() {
        Slot[] slots = inventory();
        slots[8] = Slot.OPENER;
        slots[2] = Slot.OPENER_OUTDATED;
        slots[5] = Slot.OTHER;
        Plan plan = MenuOpenerPlacement.plan(slots, false, 8, true);
        assertEquals(List.of(2, 8), plan.remove());
        assertEquals(-1, plan.place());
    }

    @Test
    void theSlotMustBeAStorageSlot() {
        assertThrows(IllegalArgumentException.class, () -> MenuOpenerPlacement.plan(inventory(), true, 36, true));
    }
}

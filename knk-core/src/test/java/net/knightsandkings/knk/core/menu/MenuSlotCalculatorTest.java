package net.knightsandkings.knk.core.menu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DESIGN_REVIEW.md §1 explicitly recommends property-based/fuzz testing for
 * slot calculation and overflow guards specifically, given this bug class has
 * recurred across three implementation generations (legacy bugs #1, #2, #8).
 * The fuzz test below is the closest fit without pulling in a property-based
 * testing library the project doesn't otherwise depend on.
 */
class MenuSlotCalculatorTest {

    @Test
    void matchesTheWorkedExampleFromQuickReferenceDoc() {
        // QUICK_REFERENCE.md: startX=2, startY=1, width=5, height=2, TOP/LEFT
        // -> displaySlot = 1*9 + 2 = 11 -> documented result: 11,12,13,14,15,20,21,22,23,24
        List<Integer> slots = MenuSlotCalculator.calculateSlots(
                11, 27, 5, 2, MenuAlignVertical.TOP, MenuAlignHorizontal.LEFT);

        assertEquals(List.of(11, 12, 13, 14, 15, 20, 21, 22, 23, 24), slots);
    }

    @Test
    void bottomRightAlignmentExtendsBackwardFromAnchor() {
        // Anchor at slot 26 (row 2, col 8 - bottom-right corner of a 27-slot menu),
        // 3x3 block aligned BOTTOM/RIGHT should extend up-and-left from the anchor.
        List<Integer> slots = MenuSlotCalculator.calculateSlots(
                26, 27, 3, 3, MenuAlignVertical.BOTTOM, MenuAlignHorizontal.RIGHT);

        assertEquals(Set.of(6, 7, 8, 15, 16, 17, 24, 25, 26), new HashSet<>(slots));
        assertTrue(MenuSlotCalculator.fitsExactly(26, 27, 3, 3, MenuAlignVertical.BOTTOM, MenuAlignHorizontal.RIGHT));
    }

    @Test
    void centerAlignmentCentersOnTheAnchor() {
        // Anchor at slot 4 (row 0, col 4 - middle of a 9-wide row), width 3 CENTER
        // should occupy columns 3,4,5.
        List<Integer> slots = MenuSlotCalculator.calculateSlots(
                4, 9, 3, 1, MenuAlignVertical.CENTER, MenuAlignHorizontal.CENTER);

        assertEquals(List.of(3, 4, 5), slots);
    }

    @Test
    void clipsRatherThanWrapsWhenASectionRunsPastTheMenuEdge() {
        // width=9 starting at column 5 of a 9-wide row can't fit - only 4 of the
        // 9 declared cells (columns 5,6,7,8) land inside the menu.
        List<Integer> slots = MenuSlotCalculator.calculateSlots(
                5, 9, 9, 1, MenuAlignVertical.TOP, MenuAlignHorizontal.LEFT);

        assertEquals(4, slots.size());
        assertFalse(MenuSlotCalculator.fitsExactly(5, 9, 9, 1, MenuAlignVertical.TOP, MenuAlignHorizontal.LEFT));
    }

    @Test
    void neverReturnsASlotOutsideAFullSizeInventoryEvenForAbsurdInputs() {
        List<Integer> slots = MenuSlotCalculator.calculateSlots(
                53, 54, 9, 6, MenuAlignVertical.TOP, MenuAlignHorizontal.LEFT);

        for (int slot : slots) {
            assertTrue(slot >= 0 && slot < 54, "slot " + slot + " outside [0, 54)");
        }
    }

    @Test
    void zeroOrNegativeDimensionsProduceNoSlots() {
        assertTrue(MenuSlotCalculator.calculateSlots(0, 27, 0, 3, MenuAlignVertical.TOP, MenuAlignHorizontal.LEFT).isEmpty());
        assertTrue(MenuSlotCalculator.calculateSlots(0, 27, 3, -1, MenuAlignVertical.TOP, MenuAlignHorizontal.LEFT).isEmpty());
    }

    @RepeatedTest(200)
    void fuzzInvariantsHoldForRandomLayouts() {
        Random random = new Random();
        // Menu sizes are always a whole number of 9-wide rows, 1 to 6 rows (FR-2.1.1).
        int menuTotalSlots = (1 + random.nextInt(6)) * MenuSlotCalculator.MENU_WIDTH;
        int width = 1 + random.nextInt(9);
        int height = 1 + random.nextInt(6);
        int displaySlot = random.nextInt(menuTotalSlots);
        MenuAlignVertical alignVertical = MenuAlignVertical.values()[random.nextInt(MenuAlignVertical.values().length)];
        MenuAlignHorizontal alignHorizontal = MenuAlignHorizontal.values()[random.nextInt(MenuAlignHorizontal.values().length)];

        List<Integer> slots = MenuSlotCalculator.calculateSlots(
                displaySlot, menuTotalSlots, width, height, alignVertical, alignHorizontal);

        // Invariant: no computed slot ever exceeds inventory size (reconciliation bug #8's guard).
        for (int slot : slots) {
            assertTrue(slot >= 0 && slot < menuTotalSlots,
                    () -> "slot " + slot + " outside [0, " + menuTotalSlots + ") for width=" + width
                            + " height=" + height + " displaySlot=" + displaySlot
                            + " align=" + alignVertical + "/" + alignHorizontal);
        }

        // Invariant: never more slots than the declared area, and never duplicates.
        Set<Integer> distinct = new HashSet<>(slots);
        assertEquals(slots.size(), distinct.size(), "calculateSlots returned duplicate slots");
        assertTrue(slots.size() <= width * height);

        // Invariant: fitsExactly is true iff nothing was clipped.
        boolean fits = MenuSlotCalculator.fitsExactly(displaySlot, menuTotalSlots, width, height, alignVertical, alignHorizontal);
        assertEquals(slots.size() == width * height, fits);
    }
}

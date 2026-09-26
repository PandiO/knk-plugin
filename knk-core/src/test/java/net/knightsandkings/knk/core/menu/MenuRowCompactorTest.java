package net.knightsandkings.knk.core.menu;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Menu follow-up 2026-09-26: DYNAMIC menus shrink to the rows they use. */
class MenuRowCompactorTest {

    private static RuntimeMenuSection section(int displaySlot, int height, int minHeight) {
        return new RuntimeMenuSection(1, "S", MenuSectionKind.CONTENT_GRID, 0, displaySlot, 9, height,
                MenuPositionMode.STATIC, MenuAlignVertical.TOP, MenuAlignHorizontal.LEFT, MenuOverflowMode.SCROLL,
                MenuListMode.DEFAULT, MenuRenderPriority.MEDIUM, null, false, List.of(), List.of(), null, Map.of(), minHeight);
    }

    private static RuntimeMenu menu(MenuGrowth growth, int minHeight, RuntimeMenuSection... sections) {
        return new RuntimeMenu("m", "M", 6, growth, null, List.of(sections), 0, minHeight, null);
    }

    @Test
    void staticMenusNeverChange() {
        MenuRowCompactor.Layout layout = MenuRowCompactor.compact(menu(MenuGrowth.STATIC, 1), Set.of(0), List.of());
        assertEquals(6, layout.height());
        assertFalse(layout.changed());
    }

    @Test
    void emptyRowsGoAndLowerRowsMoveUp() {
        // Header row 0, two content rows (1, 2), empty rows 3-4, footer (pager) row 5.
        MenuRowCompactor.Layout layout = MenuRowCompactor.compact(menu(MenuGrowth.DYNAMIC, 1),
                Set.of(8, 9, 20, 49), List.of());

        assertEquals(4, layout.height());
        assertArrayEquals(new int[] {0, 1, 2, -1, -1, 3}, layout.newRowByOldRow());
        assertEquals(31, layout.mapSlot(49), "footer row 5 moves to row 3");
        assertEquals(-1, layout.mapSlot(30));
        assertEquals(36, layout.totalSlots());
    }

    @Test
    void menuMinHeightKeepsTheTopMostEmptyRows() {
        MenuRowCompactor.Layout layout = MenuRowCompactor.compact(menu(MenuGrowth.DYNAMIC, 3), Set.of(0), List.of());
        assertEquals(3, layout.height());
        assertArrayEquals(new int[] {0, 1, 2, -1, -1, -1}, layout.newRowByOldRow());
    }

    @Test
    void sectionMinHeightProtectsItsFirstRows() {
        RuntimeMenuSection grid = section(18, 4, 2);
        MenuRowCompactor.Layout layout = MenuRowCompactor.compact(menu(MenuGrowth.DYNAMIC, 1, grid), Set.of(4), List.of(grid));

        assertArrayEquals(new int[] {0, -1, 1, 2, -1, -1}, layout.newRowByOldRow());
    }

    @Test
    void remapDropsRemovedSlots() {
        MenuRowCompactor.Layout layout = MenuRowCompactor.compact(menu(MenuGrowth.DYNAMIC, 1), Set.of(0, 45), List.of());
        Map<Integer, String> moved = new java.util.HashMap<>();
        layout.remap(Map.of(0, "a", 45, "b", 20, "gone"), moved::put);
        assertEquals(Map.of(0, "a", 9, "b"), moved);
        assertTrue(layout.changed());
    }
}

package net.knightsandkings.knk.core.menu;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeMenuTest {

    private static RuntimeMenuSection section(String name) {
        return new RuntimeMenuSection(1, name, MenuSectionKind.CONTENT_GRID, 0, 0, 9, 1,
                MenuPositionMode.STATIC, MenuAlignVertical.TOP, MenuAlignHorizontal.LEFT,
                MenuOverflowMode.HIDE, MenuListMode.DEFAULT, MenuRenderPriority.MEDIUM, null, false, List.of(), List.of());
    }

    @Test
    void totalSlotsIsHeightTimesNine() {
        RuntimeMenu menu = new RuntimeMenu("k", "t", 4, MenuGrowth.STATIC, null, List.of());

        assertEquals(36, menu.totalSlots());
    }

    @Test
    void findSectionMatchesRegardlessOfCase() {
        RuntimeMenu menu = new RuntimeMenu("k", "t", 3, MenuGrowth.STATIC, null, List.of(section("Content")));

        assertTrue(menu.findSection("content").isPresent());
        assertTrue(menu.findSection("CONTENT").isPresent());
        assertTrue(menu.findSection("Content").isPresent());
        assertEquals("Content", menu.findSection("content").orElseThrow().name());
    }

    @Test
    void findSectionIsEmptyForAnUnknownName() {
        RuntimeMenu menu = new RuntimeMenu("k", "t", 3, MenuGrowth.STATIC, null, List.of(section("Header")));

        assertTrue(menu.findSection("content").isEmpty());
    }
}

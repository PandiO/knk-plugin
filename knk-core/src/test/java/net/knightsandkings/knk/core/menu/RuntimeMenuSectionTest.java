package net.knightsandkings.knk.core.menu;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link RuntimeMenuSection#resolveSlots}: the base-class overflow/
 * pagination implementation that closes reconciliation gap #9 (v2's
 * {@code Menu.nextPage}/{@code prevPage} were no-op stubs only some
 * subclasses overrode - here there is exactly one concrete section class, so
 * there's nothing for a "future screen" to forget to override).
 */
class RuntimeMenuSectionTest {

    private static final int MENU_TOTAL_SLOTS = 27;

    private static RuntimeMenuItem item(int sortOrder) {
        return item(sortOrder, null);
    }

    private static RuntimeMenuItem item(int sortOrder, Integer slotOverride) {
        return new RuntimeMenuItem(sortOrder, sortOrder, slotOverride, null, 1,
                null, null, MenuDisplayMode.NORMAL, null, null, List.of(), List.of(), List.of());
    }

    private static RuntimeMenuSection section(MenuOverflowMode overflow, List<RuntimeMenuItem> items) {
        // A 2x2 (capacity 4) content section starting at slot 0.
        return new RuntimeMenuSection(1, "content", MenuSectionKind.CONTENT_GRID, 0, 0, 2, 2,
                MenuPositionMode.STATIC, MenuAlignVertical.TOP, MenuAlignHorizontal.LEFT,
                overflow, MenuListMode.DEFAULT, MenuRenderPriority.MEDIUM, null, items, List.of());
    }

    @Test
    void hideOverflowTruncatesToOnePageWithNoNavigation() {
        List<RuntimeMenuItem> items = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            items.add(item(i));
        }
        RuntimeMenuSection section = section(MenuOverflowMode.HIDE, items);

        SectionSlotAssignment assignment = section.resolveSlots(MENU_TOTAL_SLOTS, 0);

        assertEquals(1, assignment.totalPages());
        assertEquals(4, assignment.itemsBySlot().size());
        assertFalse(assignment.hasNextPage());
        assertFalse(assignment.hasPreviousPage());
        // First 4 by sortOrder (0,1,2,3), item 4 dropped.
        assertEquals(List.of(0, 1, 2, 3), assignment.itemsBySlot().values().stream()
                .map(RuntimeMenuItem::sortOrder).sorted().toList());
    }

    @Test
    void scrollOverflowPaginatesAcrossMultiplePages() {
        List<RuntimeMenuItem> items = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            items.add(item(i));
        }
        RuntimeMenuSection section = section(MenuOverflowMode.SCROLL, items);

        SectionSlotAssignment page0 = section.resolveSlots(MENU_TOTAL_SLOTS, 0);
        assertEquals(2, page0.totalPages());
        assertEquals(4, page0.itemsBySlot().size());
        assertTrue(page0.hasNextPage());
        assertFalse(page0.hasPreviousPage());

        SectionSlotAssignment page1 = section.resolveSlots(MENU_TOTAL_SLOTS, 1);
        assertEquals(1, page1.itemsBySlot().size());
        assertFalse(page1.hasNextPage());
        assertTrue(page1.hasPreviousPage());
    }

    @Test
    void wrapOverflowAlsoPaginatesLikeScroll() {
        List<RuntimeMenuItem> items = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            items.add(item(i));
        }
        RuntimeMenuSection section = section(MenuOverflowMode.WRAP, items);

        SectionSlotAssignment page0 = section.resolveSlots(MENU_TOTAL_SLOTS, 0);
        assertEquals(2, page0.totalPages());
        assertTrue(page0.hasNextPage());
    }

    @Test
    void requestedPageIsClampedToValidRange() {
        List<RuntimeMenuItem> items = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            items.add(item(i));
        }
        RuntimeMenuSection section = section(MenuOverflowMode.SCROLL, items);

        assertEquals(0, section.resolveSlots(MENU_TOTAL_SLOTS, -5).page());
        assertEquals(1, section.resolveSlots(MENU_TOTAL_SLOTS, 999).page());
    }

    @Test
    void pinnedItemsAlwaysOccupyTheirExplicitSlotAndDontCountTowardPagination() {
        List<RuntimeMenuItem> items = new ArrayList<>();
        items.add(item(0, 0)); // pinned to slot 0, inside this section's own 2x2 footprint
        for (int i = 1; i <= 4; i++) {
            items.add(item(i)); // 4 auto items competing for the remaining 3 slots
        }
        RuntimeMenuSection section = section(MenuOverflowMode.SCROLL, items);

        SectionSlotAssignment page0 = section.resolveSlots(MENU_TOTAL_SLOTS, 0);
        assertEquals(0, section.resolveSlots(MENU_TOTAL_SLOTS, 0).page());
        assertEquals(item(0, 0), page0.itemsBySlot().get(0));
        // Pool is 3 slots (1,2,3) now, 4 auto items -> 2 pages.
        assertEquals(2, page0.totalPages());

        SectionSlotAssignment page1 = section.resolveSlots(MENU_TOTAL_SLOTS, 1);
        // Pinned item still occupies slot 0 on every page.
        assertEquals(item(0, 0), page1.itemsBySlot().get(0));
    }

    @Test
    void emptySectionHasZeroPagesAndNoAssignments() {
        RuntimeMenuSection section = section(MenuOverflowMode.SCROLL, List.of());

        SectionSlotAssignment assignment = section.resolveSlots(MENU_TOTAL_SLOTS, 0);

        assertEquals(0, assignment.totalPages());
        assertTrue(assignment.itemsBySlot().isEmpty());
        assertFalse(assignment.hasNextPage());
        assertFalse(assignment.hasPreviousPage());
    }
}

package net.knightsandkings.knk.core.menu;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
        return section(overflow, items, false);
    }

    private static RuntimeMenuSection section(MenuOverflowMode overflow, List<RuntimeMenuItem> items, boolean searchable) {
        // A 2x2 (capacity 4) content section starting at slot 0.
        return new RuntimeMenuSection(1, "content", MenuSectionKind.CONTENT_GRID, 0, 0, 2, 2,
                MenuPositionMode.STATIC, MenuAlignVertical.TOP, MenuAlignHorizontal.LEFT,
                overflow, MenuListMode.DEFAULT, MenuRenderPriority.MEDIUM, null, searchable, items, List.of(), null, null);
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

    /**
     * IMPLEMENTATION_PLAN.md Phase 4 / DESIGN_REVIEW.md §2.4: a section's
     * visibilityPermission is a whole-section gate, closing reconciliation
     * gap #10 (v2's debug-only Caches button had no permission gate at all).
     */
    @Test
    void nullVisibilityPermissionIsAlwaysVisible() {
        RuntimeMenuSection section = section(MenuOverflowMode.HIDE, List.of());

        assertTrue(section.isVisibleTo(node -> false));
    }

    @Test
    void nonNullVisibilityPermissionDelegatesToChecker() {
        RuntimeMenuSection base = section(MenuOverflowMode.HIDE, List.of());
        RuntimeMenuSection gated = new RuntimeMenuSection(base.id(), base.name(), base.kind(), base.sortOrder(),
                base.displaySlot(), base.width(), base.height(), base.positionMode(), base.alignVertical(),
                base.alignHorizontal(), base.overflow(), base.listMode(), base.priority(),
                "knk.menu.example.debug", base.searchable(), base.items(), base.variableBindings(),
                base.contentSourceId(), base.contentSourceParams());

        assertFalse(gated.isVisibleTo(node -> false));
        assertTrue(gated.isVisibleTo(node -> node.equals("knk.menu.example.debug")));
    }

    /**
     * IMPLEMENTATION_PLAN.md Phase 5 / DESIGN_REVIEW.md §2.1: search/filter
     * must narrow the content list BEFORE pagination runs, not conflict with
     * it - narrowing 5 items to 2 with a 4-capacity section should yield one
     * page, not the two pages the unfiltered set would need.
     */
    @Test
    void contentFilterNarrowsBeforePaginationRuns() {
        List<RuntimeMenuItem> items = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            items.add(item(i));
        }
        RuntimeMenuSection section = section(MenuOverflowMode.SCROLL, items, true);

        SectionSlotAssignment filtered = section.resolveSlots(MENU_TOTAL_SLOTS, 0,
                candidate -> candidate.sortOrder() < 2);

        assertEquals(1, filtered.totalPages());
        assertEquals(2, filtered.itemsBySlot().size());
        assertFalse(filtered.hasNextPage());
        assertEquals(List.of(0, 1), filtered.itemsBySlot().values().stream()
                .map(RuntimeMenuItem::sortOrder).sorted().toList());
    }

    @Test
    void contentFilterExcludingEverythingYieldsZeroPages() {
        List<RuntimeMenuItem> items = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            items.add(item(i));
        }
        RuntimeMenuSection section = section(MenuOverflowMode.SCROLL, items, true);

        SectionSlotAssignment filtered = section.resolveSlots(MENU_TOTAL_SLOTS, 0, candidate -> false);

        assertEquals(0, filtered.totalPages());
        assertTrue(filtered.itemsBySlot().isEmpty());
    }

    /**
     * A pinned item (e.g. a persistent search/clear-filter button) opts out
     * of pagination already; a content filter must not exclude it either -
     * only the auto-placed content it sits alongside gets narrowed.
     */
    @Test
    void contentFilterDoesNotExcludePinnedItems() {
        List<RuntimeMenuItem> items = new ArrayList<>();
        items.add(item(0, 0)); // pinned to slot 0
        for (int i = 1; i <= 3; i++) {
            items.add(item(i));
        }
        RuntimeMenuSection section = section(MenuOverflowMode.SCROLL, items, true);

        SectionSlotAssignment filtered = section.resolveSlots(MENU_TOTAL_SLOTS, 0, candidate -> false);

        assertEquals(item(0, 0), filtered.itemsBySlot().get(0));
        assertEquals(1, filtered.itemsBySlot().size());
    }

    @Test
    void noContentFilterArgumentBehavesAsUnfiltered() {
        List<RuntimeMenuItem> items = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            items.add(item(i));
        }
        RuntimeMenuSection section = section(MenuOverflowMode.SCROLL, items, true);

        assertEquals(section.resolveSlots(MENU_TOTAL_SLOTS, 0).itemsBySlot(),
                section.resolveSlots(MENU_TOTAL_SLOTS, 0, candidate -> true).itemsBySlot());
    }

    /**
     * IMPLEMENTATION_PLAN.md Phase 8: a section with no {@code contentSourceId}
     * (the default for every pre-Phase-8 section) reports
     * {@link RuntimeMenuSection#hasContentSource()} false, and {@code null}
     * {@code contentSourceParams} normalizes to an empty map rather than null.
     */
    @Test
    void sectionWithNoContentSourceIdReportsHasContentSourceFalse() {
        RuntimeMenuSection section = section(MenuOverflowMode.HIDE, List.of());

        assertFalse(section.hasContentSource());
        assertEquals(Map.of(), section.contentSourceParams());
    }

    @Test
    void blankContentSourceIdAlsoReportsHasContentSourceFalse() {
        RuntimeMenuSection base = section(MenuOverflowMode.HIDE, List.of());
        RuntimeMenuSection blank = new RuntimeMenuSection(base.id(), base.name(), base.kind(), base.sortOrder(),
                base.displaySlot(), base.width(), base.height(), base.positionMode(), base.alignVertical(),
                base.alignHorizontal(), base.overflow(), base.listMode(), base.priority(),
                base.visibilityPermission(), base.searchable(), base.items(), base.variableBindings(),
                "  ", base.contentSourceParams());

        assertFalse(blank.hasContentSource());
    }

    @Test
    void contentSourceIdMakesHasContentSourceTrue() {
        RuntimeMenuSection base = section(MenuOverflowMode.HIDE, List.of());
        RuntimeMenuSection backed = new RuntimeMenuSection(base.id(), base.name(), base.kind(), base.sortOrder(),
                base.displaySlot(), base.width(), base.height(), base.positionMode(), base.alignVertical(),
                base.alignHorizontal(), base.overflow(), base.listMode(), base.priority(),
                base.visibilityPermission(), base.searchable(), base.items(), base.variableBindings(),
                "catalog.itemblueprints", Map.of("kind", "example"));

        assertTrue(backed.hasContentSource());
        assertEquals("example", backed.contentSourceParams().get("kind"));
    }

    /**
     * IMPLEMENTATION_PLAN.md Phase 8: {@link RuntimeMenuSection#computeSlotPool}
     * is the shared pinned/pool split both the legacy in-memory {@link
     * RuntimeMenuSection#resolveSlots} path and knk-paper's new
     * content-source-backed render path use - a pinned control button (e.g. a
     * Next-page/Search button) must be excluded from the pool the content
     * source pages into, exactly like it's excluded from the auto/paginated
     * pool today.
     */
    @Test
    void computeSlotPoolExcludesPinnedSlotsFromThePool() {
        // The 2x2 section helper below is anchored at slot 0, so its footprint
        // is {0, 1, 9, 10} (row-major, 9-wide) - slots 0 and 9 pin the first
        // cell of each row.
        List<RuntimeMenuItem> items = List.of(item(0, 0), item(1, 9));
        RuntimeMenuSection section = section(MenuOverflowMode.SCROLL, items);

        RuntimeMenuSection.SlotPool pool = section.computeSlotPool(MENU_TOTAL_SLOTS);

        assertEquals(2, pool.pinnedBySlot().size());
        assertEquals(item(0, 0), pool.pinnedBySlot().get(0));
        assertEquals(item(1, 9), pool.pinnedBySlot().get(9));
        assertFalse(pool.availablePool().contains(0));
        assertFalse(pool.availablePool().contains(9));
        // A 2x2 section (capacity 4) minus 2 pinned slots leaves a 2-slot pool.
        assertEquals(2, pool.availablePool().size());
        assertEquals(List.of(1, 10), pool.availablePool());
    }

    @Test
    void computeSlotPoolWithNoPinnedItemsReturnsTheWholeFootprint() {
        RuntimeMenuSection section = section(MenuOverflowMode.SCROLL, List.of());

        RuntimeMenuSection.SlotPool pool = section.computeSlotPool(MENU_TOTAL_SLOTS);

        assertTrue(pool.pinnedBySlot().isEmpty());
        assertEquals(4, pool.availablePool().size());
    }

    /**
     * A content-source-backed section's own {@link RuntimeMenuSection#items()}
     * is expected to hold only pinned control buttons - {@link
     * RuntimeMenuSection#resolveSlots} always reports zero auto content for
     * such a section (knk-paper's {@code MenuRenderer} is expected to branch
     * on {@link RuntimeMenuSection#hasContentSource()} before calling this at
     * all, but this asserts the safe-by-construction fallback too).
     */
    @Test
    void resolveSlotsReportsNoAutoContentForAContentSourceBackedSection() {
        RuntimeMenuSection base = section(MenuOverflowMode.SCROLL, List.of(item(0), item(1), item(2)));
        RuntimeMenuSection backed = new RuntimeMenuSection(base.id(), base.name(), base.kind(), base.sortOrder(),
                base.displaySlot(), base.width(), base.height(), base.positionMode(), base.alignVertical(),
                base.alignHorizontal(), base.overflow(), base.listMode(), base.priority(),
                base.visibilityPermission(), base.searchable(), base.items(), base.variableBindings(),
                "catalog.itemblueprints", Map.of());

        SectionSlotAssignment assignment = backed.resolveSlots(MENU_TOTAL_SLOTS, 0);

        assertEquals(0, assignment.totalPages());
        assertTrue(assignment.itemsBySlot().isEmpty());
    }
}

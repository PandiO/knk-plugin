package net.knightsandkings.knk.paper.menu;

import net.knightsandkings.knk.core.domain.menu.KnkActionBinding;
import net.knightsandkings.knk.core.menu.MenuDisplayMode;
import net.knightsandkings.knk.core.menu.RuntimeMenuItem;
import net.knightsandkings.knk.core.menu.RuntimeMenuSection;
import net.knightsandkings.knk.core.menu.SectionView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * KNG-231: one page load per player at a time (repeat clicks during a load are ignored), a hung
 * load stops blocking, and the loading feedback finds the section's pager buttons and names the
 * page being fetched.
 */
class MenuPageLoadsTest {

    private static final UUID PLAYER = UUID.randomUUID();

    private static RuntimeMenuItem pinned(int id, int slot, String... actions) {
        List<KnkActionBinding> bindings = java.util.Arrays.stream(actions)
                .map(a -> new KnkActionBinding(id, a, "{}", 0, List.of())).toList();
        return new RuntimeMenuItem(id, id, slot, null, 1, null, null, MenuDisplayMode.NORMAL, null, null, List.of(),
                bindings, List.of());
    }

    private static RuntimeMenuSection section(int id) {
        RuntimeMenuSection section = mock(RuntimeMenuSection.class);
        when(section.id()).thenReturn(id);
        return section;
    }

    @Test
    void aSecondClickDuringALoadIsIgnoredUntilItFinishes() {
        MenuPageLoads loads = new MenuPageLoads();

        OptionalLong first = loads.tryBegin(PLAYER, 1_000L);
        assertTrue(first.isPresent());
        assertTrue(loads.tryBegin(PLAYER, 1_050L).isEmpty(), "double click while loading");
        assertTrue(loads.isCurrent(PLAYER, first.getAsLong()));
        assertTrue(loads.tryBegin(UUID.randomUUID(), 1_050L).isPresent(), "other players are not blocked");

        loads.finish(PLAYER, first.getAsLong());
        assertFalse(loads.isLoading(PLAYER));
        assertTrue(loads.tryBegin(PLAYER, 1_100L).isPresent());
    }

    @Test
    void aHungLoadStopsBlockingAndItsLateFinishLeavesTheNewOneAlone() {
        MenuPageLoads loads = new MenuPageLoads();
        long hung = loads.tryBegin(PLAYER, 0L).getAsLong();

        assertTrue(loads.tryBegin(PLAYER, MenuPageLoads.STUCK_MILLIS - 1).isEmpty());
        long next = loads.tryBegin(PLAYER, MenuPageLoads.STUCK_MILLIS).getAsLong();

        assertFalse(loads.isCurrent(PLAYER, hung));
        loads.finish(PLAYER, hung);
        assertTrue(loads.isCurrent(PLAYER, next), "the old load finishing late doesn't end the new one");
    }

    @Test
    void cancelEndsTheLoadSoItsResultIsDropped() {
        MenuPageLoads loads = new MenuPageLoads();
        long token = loads.tryBegin(PLAYER, 0L).getAsLong();

        loads.cancel(PLAYER);

        assertFalse(loads.isCurrent(PLAYER, token));
        assertTrue(loads.tryBegin(PLAYER, 1L).isPresent());
    }

    @Test
    void pagerSlotsAreTheSectionsNextPreviousAndFirstPageButtons() {
        RuntimeMenuSection items = section(1);
        RuntimeMenuSection other = section(2);
        Map<Integer, RuntimeMenuItem> bySlot = Map.of(
                45, pinned(1, 45, "menu.page.prev"),
                53, pinned(2, 53, "menu.page.next"),
                49, pinned(3, 49, "menu.search.prompt"),
                47, pinned(4, 47, "menu.page.first"),
                0, pinned(5, 0, "menu.page.next"));
        Map<Integer, RuntimeMenuSection> sections = Map.of(45, items, 53, items, 49, items, 47, items, 0, other);

        assertEquals(Set.of(45, 47, 53), MenuPageLoads.pagerSlots(bySlot, sections, items));
        assertEquals(Set.of(0), MenuPageLoads.pagerSlots(bySlot, sections, other));
    }

    @Test
    void displayPageWrapsUnclampedStepsWithTheLastPageCount() {
        SectionView pageOneOfFive = new SectionView("Items", 0, 5);

        assertEquals(2, MenuPageLoads.displayPage(1, pageOneOfFive));
        assertEquals(5, MenuPageLoads.displayPage(-1, pageOneOfFive), "previous from page 1");
        assertEquals(1, MenuPageLoads.displayPage(5, pageOneOfFive), "next from the last page");
        assertEquals(1, MenuPageLoads.displayPage(0, null));
        assertEquals("&7Loading page &f3&7…", MenuPageLoads.loadingText(3));
    }
}

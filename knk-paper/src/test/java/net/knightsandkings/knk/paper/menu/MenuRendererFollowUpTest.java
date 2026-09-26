package net.knightsandkings.knk.paper.menu;

import net.knightsandkings.knk.core.domain.menu.KnkActionBinding;
import net.knightsandkings.knk.core.menu.MenuDisplayMode;
import net.knightsandkings.knk.core.menu.RuntimeMenuItem;
import net.knightsandkings.knk.core.menu.SectionSlotAssignment;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Menu follow-up 2026-09-26: pager arrows are hidden while a section has at most one page. */
class MenuRendererFollowUpTest {

    private static RuntimeMenuItem pinned(int id, int slot, String... actions) {
        List<KnkActionBinding> bindings = java.util.Arrays.stream(actions)
                .map(a -> new KnkActionBinding(id, a, "{}", 0, List.of())).toList();
        return new RuntimeMenuItem(id, id, slot, null, 1, null, null, MenuDisplayMode.NORMAL, null, null, List.of(),
                bindings, List.of());
    }

    @Test
    void pagersDisappearOnASinglePageAndReturnWithMore() {
        Map<Integer, RuntimeMenuItem> items = Map.of(
                45, pinned(1, 45, "menu.page.prev"),
                53, pinned(2, 53, "menu.page.next"),
                49, pinned(3, 49, "menu.search.prompt"),
                8, pinned(4, 8, "menu.back"));

        Map<Integer, RuntimeMenuItem> single = MenuRenderer.withoutIdlePagers(new SectionSlotAssignment(items, 0, 1, false, false));
        Map<Integer, RuntimeMenuItem> empty = MenuRenderer.withoutIdlePagers(new SectionSlotAssignment(items, 0, 0, false, false));
        Map<Integer, RuntimeMenuItem> many = MenuRenderer.withoutIdlePagers(new SectionSlotAssignment(items, 0, 3, true, false));

        assertEquals(java.util.Set.of(49, 8), single.keySet());
        assertEquals(java.util.Set.of(49, 8), empty.keySet());
        assertEquals(items.keySet(), many.keySet());
    }
}

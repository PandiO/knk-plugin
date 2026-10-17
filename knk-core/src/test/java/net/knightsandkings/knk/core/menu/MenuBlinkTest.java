package net.knightsandkings.knk.core.menu;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static net.knightsandkings.knk.core.menu.Phase9Fixtures.binding;
import static net.knightsandkings.knk.core.menu.Phase9Fixtures.item;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** KNG-125: the item blinker's timing and how a template switches it on (BLINK display mode, BlinkTicks). */
class MenuBlinkTest {

    private final MenuSession session = new MenuSession(UUID.randomUUID());

    @Test
    void theItemShowsForOneIntervalThenThePaneThenTheItemAgain() {
        long start = 1_000L;
        assertFalse(MenuBlink.flashShown(start, start, 10));
        assertFalse(MenuBlink.flashShown(start, start + 9, 10));
        assertTrue(MenuBlink.flashShown(start, start + 10, 10));
        assertTrue(MenuBlink.flashShown(start, start + 19, 10));
        assertFalse(MenuBlink.flashShown(start, start + 20, 10));
        assertTrue(MenuBlink.flashShown(start, start + 35, 10));
        assertFalse(MenuBlink.flashShown(start, start - 5, 10), "before the start the item is shown");
    }

    @Test
    void theIntervalDefaultsToTenTicksAndIsClamped() {
        assertEquals(10, MenuBlink.intervalTicks(null));
        assertEquals(MenuBlink.MIN_INTERVAL_TICKS, MenuBlink.intervalTicks(0));
        assertEquals(MenuBlink.MIN_INTERVAL_TICKS, MenuBlink.intervalTicks(-4));
        assertEquals(MenuBlink.MAX_INTERVAL_TICKS, MenuBlink.intervalTicks(100_000));
        assertEquals(20, MenuBlink.intervalTicks(20));
        // A zero interval reaching flashShown is clamped instead of dividing by zero.
        assertTrue(MenuBlink.flashShown(0L, 2L, 0));
    }

    @Test
    void aDisplayModeBindingOfBlinkKeepsTheItemClickableWithItsActions() {
        RuntimeMenuItem selected = Phase9Fixtures.item(1, 4, List.of(binding(1, "DisplayMode", "blink")),
                List.of(Phase9Fixtures.action(1, "statistics.visibility.select-group", "{\"group\":\"combat\"}", List.of())),
                List.of(), false);
        MenuSectionRenderer.Pass<Object> pass = new MenuSectionRenderer.Pass<>(session, 0L, node -> true,
                new ConditionRegistry<>(), (item, scope) -> null);

        List<MenuSectionRenderer.RenderedSlot> rendered = MenuSectionRenderer.renderItems(Map.of(4, selected),
                MenuVariableScope.of(Map.of()), pass);

        assertEquals(1, rendered.size());
        assertEquals(MenuDisplayMode.BLINK, rendered.get(0).item().displayMode());
        assertEquals(1, rendered.get(0).item().actions().size(), "the blinking item keeps its actions");
        assertNull(rendered.get(0).presentation().blinkTicks(), "no BlinkTicks binding: the default interval");
    }

    @Test
    void aBlinkTicksBindingSetsTheIntervalAndABadValueWarns() {
        RuntimeMenuItem slow = item(1, 4, List.of(binding(1, "DisplayMode", "BLINK"), binding(2, "BlinkTicks", "20")));
        RuntimeMenuItem fast = item(2, 5, List.of(binding(3, "BlinkTicks", "1")));
        RuntimeMenuItem bad = item(3, 6, List.of(binding(4, "BlinkTicks", "often"))); // binding ids are cache keys
        Map<String, Object> scope = MenuVariableScope.of(Map.of());

        MenuItemPresentation slowPresentation = MenuItemPresentation.resolve(slow, session, scope, 0L, null);
        assertEquals(MenuDisplayMode.BLINK, slowPresentation.displayMode());
        assertEquals(20, slowPresentation.blinkTicks());
        assertTrue(slowPresentation.warnings().isEmpty());

        assertEquals(MenuBlink.MIN_INTERVAL_TICKS, MenuItemPresentation.resolve(fast, session, scope, 0L, null).blinkTicks());

        MenuItemPresentation badPresentation = MenuItemPresentation.resolve(bad, session, scope, 0L, null);
        assertNull(badPresentation.blinkTicks());
        assertEquals(1, badPresentation.warnings().size());
    }

    @Test
    void aBlinkColumnValueParsesFromTheApiSpelling() {
        assertEquals(MenuDisplayMode.BLINK,
                MenuEnumParsing.parse(MenuDisplayMode.class, "Blink", MenuDisplayMode.NORMAL, "displayMode", "test"));
    }
}

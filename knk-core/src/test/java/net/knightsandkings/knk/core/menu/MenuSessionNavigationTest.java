package net.knightsandkings.knk.core.menu;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** InventoryMenu Phase 9, E1 (context parameters) + E9 (back navigation): the nav stack stores (key, ctx). */
class MenuSessionNavigationTest {

    private static MenuContextParams ctx(String key, String value) {
        return MenuContextParams.of(Map.of(key, value));
    }

    @Test
    void ctxPrefixedParamsBecomeContextWithPrefixStripped() {
        Map<String, String> params = new HashMap<>();
        params.put("key", "siege.information");
        params.put("ctx.lobbyId", "3");
        params.put("ctx.", "ignored - empty name");
        params.put("other", "x");

        MenuContextParams context = MenuContextParams.fromPrefixedParams(params);

        assertEquals(Map.of("lobbyId", "3"), context.asMap());
        assertEquals("3", context.get("lobbyId"));
        assertNull(context.get("missing"));
        assertTrue(MenuContextParams.fromPrefixedParams(Map.of("key", "x")).isEmpty());
        assertSame(MenuContextParams.EMPTY, MenuContextParams.of(null));
    }

    @Test
    void contextParamsCompareByValue() {
        assertEquals(ctx("lobbyId", "3"), ctx("lobbyId", "3"));
        assertFalse(ctx("lobbyId", "3").equals(ctx("lobbyId", "4")));
    }

    @Test
    void backNavigationRestoresBothKeyAndContext() {
        MenuSession session = new MenuSession(UUID.randomUUID());
        session.openAsRoot("siege.overview", MenuContextParams.EMPTY, "&eSiege Minigame");
        session.navigateTo("siege.information", ctx("lobbyId", "3"), "Siege Information");
        session.navigateTo("siege.information", ctx("lobbyId", "4"), "Siege Information");

        assertEquals("4", session.currentContext().get("lobbyId"));
        assertTrue(session.hasPrevious());

        MenuSession.NavigationEntry back = session.goBackEntry().orElseThrow();
        assertEquals("siege.information", back.key());
        assertEquals("3", back.context().get("lobbyId"));
        assertEquals("3", session.currentContext().get("lobbyId"));

        assertEquals("siege.overview", session.goBackEntry().orElseThrow().key());
        assertTrue(session.currentContext().isEmpty());
        assertFalse(session.hasPrevious());
        assertTrue(session.goBackEntry().isEmpty(), "empty stack: the caller closes the menu");
    }

    @Test
    void reopeningTheSameKeyAndContextReplacesInsteadOfPushing() {
        MenuSession session = new MenuSession(UUID.randomUUID());
        session.openAsRoot("a", MenuContextParams.EMPTY, "A");
        session.navigateTo("b", ctx("id", "1"), "B");
        session.navigateTo("b", ctx("id", "1"), "B");

        assertEquals("a", session.previousEntry().orElseThrow().key());
        session.goBackEntry();
        assertFalse(session.hasPrevious());
    }

    @Test
    void openAsRootClearsTheBackStack() {
        MenuSession session = new MenuSession(UUID.randomUUID());
        session.navigateTo("a");
        session.navigateTo("b");
        session.openAsRoot("c", ctx("id", "9"), "C");

        assertFalse(session.hasPrevious());
        assertEquals("c", session.currentMenuKey().orElseThrow());
        assertEquals("9", session.currentContext().get("id"));
    }

    @Test
    void navigationStartsTheNewMenuWithAnEmptyVariableCache() {
        MenuSession session = new MenuSession(UUID.randomUUID());
        session.openAsRoot("a", ctx("lobbyId", "3"), "A");
        session.cacheVariable(1, new MenuSession.CachedVariable("lobby 3", 0L));

        session.navigateTo("a", ctx("lobbyId", "4"), "A");

        assertTrue(session.getCachedVariable(1).isEmpty(), "a different ctx must never show the previous ctx's cached values");
    }

    @Test
    void menuViewDerivesBackLabelAndHintFromTheStack() {
        MenuSession session = new MenuSession(UUID.randomUUID());
        session.openAsRoot("siege.overview", MenuContextParams.EMPTY, "&eSiege &aMinigame");

        MenuView root = MenuView.of("siege.overview", "&eSiege &aMinigame", session);
        assertEquals("Exit", root.getBackLabel());
        assertEquals("Click here to close this menu", root.getBackHint());
        assertFalse(root.hasPrevious());

        session.navigateTo("siege.information", ctx("lobbyId", "3"), "Siege Information");
        MenuView child = MenuView.of("siege.information", "Siege Information", session);
        assertEquals("Back", child.getBackLabel());
        assertEquals("Click here to go back to Siege Minigame", child.getBackHint(), "colour codes stripped");
        assertEquals("Siege Minigame", child.getPreviousTitle());
    }

    @Test
    void sectionViewIsOneBasedAndNeverReportsZeroPages() {
        SectionView empty = new SectionView("Sieges", 0, 0);
        assertEquals(1, empty.getPage());
        assertEquals(1, empty.getPageCount());
        assertFalse(empty.hasNextPage());

        SectionView middle = new SectionView("Sieges", 1, 3);
        assertEquals(2, middle.getPage());
        assertEquals(3, middle.getPageCount());
        assertTrue(middle.hasNextPage());
        assertTrue(middle.hasPreviousPage());
    }
}

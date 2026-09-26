package net.knightsandkings.knk.paper.menu;

import net.knightsandkings.knk.core.menu.ActionRegistry;
import net.knightsandkings.knk.core.menu.MenuContextParams;
import net.knightsandkings.knk.core.menu.MenuSession;
import net.knightsandkings.knk.core.menu.MenuSessionRegistry;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Content port CP6 (engine gap G1): {@code menu.state.set}/{@code menu.state.cycle} and {@code menu.open state.*}. */
class MenuStateActionsTest {

    private final ActionRegistry<MenuActionContext> actions = new ActionRegistry<>();
    private final MenuService menuService = mock(MenuService.class);
    private final Player player = mock(Player.class);
    private final MenuSession session = new MenuSessionRegistry().open(UUID.randomUUID());

    MenuStateActionsTest() {
        MenuActionHandlers.registerDefaults(actions);
    }

    private MenuActionContext context() {
        return new MenuActionContext(player, session, Map.of(), menuService, null, null, null, null);
    }

    @Test
    void setStoresMarksDirtyAndRepaints() {
        session.clearDirty();

        actions.execute("menu.state.set", context(), Map.of("key", "pm.coinStep", "value", "1000"));

        assertEquals("1000", session.getState("pm.coinStep"));
        assertTrue(session.isDirty());
        verify(menuService).refreshOpenMenu(player);

        actions.execute("menu.state.set", context(), Map.of("key", "pm.coinStep", "value", ""));
        assertNull(session.getState("pm.coinStep"), "empty value unsets");
    }

    @Test
    void cycleWalksTheTrimmedListAndWraps() {
        Map<String, String> params = Map.of("key", "pm.gemStep", "values", "1, 10 ,100,1000");

        actions.execute("menu.state.cycle", context(), params);
        assertEquals("1", session.getState("pm.gemStep"));
        session.setState("pm.gemStep", "1000");
        actions.execute("menu.state.cycle", context(), params);
        assertEquals("1", session.getState("pm.gemStep"));
        actions.execute("menu.state.cycle", context(), params);
        assertEquals("10", session.getState("pm.gemStep"));
        verify(menuService, times(3)).refreshOpenMenu(player);
    }

    @Test
    void badParamsFailLoudly() {
        assertThrows(RuntimeException.class, () -> actions.execute("menu.state.cycle", context(), Map.of("key", "k", "values", " , ")));
        assertThrows(RuntimeException.class, () -> actions.execute("menu.state.set", context(), Map.of("value", "1")));
    }

    @Test
    void menuOpenSetsStateDefaultsOnlyIfUnsetAndPassesCtx() {
        session.setState("pm.coinStep", "1000");

        actions.execute("menu.open", context(), Map.of("key", "users.manager.edit", "ctx.userId", "7",
                "state.pm.coinStep", "100", "state.pm.gemStep", "10"));

        assertEquals("1000", session.getState("pm.coinStep"), "the step the player picked survives");
        assertEquals("10", session.getState("pm.gemStep"));
        verify(menuService).openMenu(player, "users.manager.edit", MenuContextParams.of(Map.of("userId", "7")));
    }
}

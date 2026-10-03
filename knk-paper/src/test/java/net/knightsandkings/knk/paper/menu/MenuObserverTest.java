package net.knightsandkings.knk.paper.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.menu.MenuContextParams;
import net.knightsandkings.knk.core.menu.MenuRefreshSchedule;
import net.knightsandkings.knk.core.menu.RuntimeMenu;
import net.knightsandkings.knk.core.menu.MenuSessionRegistry;

/**
 * The menu hook for diagnostics and funnels (KNG-34 link 6, IMPLEMENTATION_PLAN.md §5.2): observers
 * get opened/back/closed/clicked/action calls, a throwing observer never breaks the menu or the
 * others, and a MenuService without observers behaves as before.
 */
class MenuObserverTest {

    static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000077");

    static final class Recorder implements MenuObserver {
        final List<String> calls = new ArrayList<>();

        @Override
        public void menuOpened(Player player, String menuKey, String parentMenuKey) {
            calls.add("opened " + menuKey + " from " + parentMenuKey);
        }

        @Override
        public void menuBack(Player player, String fromMenuKey, String toMenuKey) {
            calls.add("back " + fromMenuKey + " -> " + toMenuKey);
        }

        @Override
        public void menuClosed(UUID playerId, String menuKey) {
            calls.add("closed " + menuKey);
        }

        @Override
        public void slotClicked(Player player, String menuKey, int slot, String itemKey, String clickType) {
            calls.add("clicked " + menuKey + "#" + slot + " " + itemKey + " " + clickType);
        }

        @Override
        public void actionExecuted(Player player, String menuKey, String actionTypeId, int slot, ActionOutcome outcome) {
            calls.add("action " + actionTypeId + " " + outcome);
        }
    }

    @Test
    void observers_runInOrder_andAThrowingOneIsSkipped() {
        MenuObservers observers = new MenuObservers();
        Recorder recorder = new Recorder();
        observers.add(new MenuObserver() {
            @Override
            public void menuOpened(Player player, String menuKey, String parentMenuKey) {
                throw new IllegalStateException("boom");
            }
        });
        observers.add(recorder);
        observers.add(null);
        Player player = mock(Player.class);

        observers.opened(player, "statistics.main", "profile.main");
        observers.back(player, "statistics.main", "profile.main");
        observers.closed(ID, "profile.main");
        observers.closed(ID, null);
        observers.clicked(player, "profile.main", 5, "12", "LEFT");
        observers.action(player, "profile.main", "menu.open", 5, MenuObserver.ActionOutcome.SUCCEEDED);

        assertEquals(List.of(
            "opened statistics.main from profile.main",
            "back statistics.main -> profile.main",
            "closed profile.main",
            "clicked profile.main#5 12 LEFT",
            "action menu.open SUCCEEDED"), recorder.calls);
    }

    @Test
    void defaultMethods_doNothing() {
        MenuObserver nothing = new MenuObserver() { };
        nothing.menuOpened(null, "a", null);
        nothing.menuBack(null, "a", "b");
        nothing.menuClosed(ID, "a");
        nothing.slotClicked(null, "a", 0, "1", "LEFT");
        nothing.actionExecuted(null, "a", "x", 0, MenuObserver.ActionOutcome.FAILED);
        assertTrue(new MenuObservers().isEmpty());
    }

    private static MenuService service(OpenMenuContextRegistry contexts, MenuSessionRegistry sessions) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        return new MenuService(plugin, null, sessions, contexts, null, null, new MenuRefreshSchedule());
    }

    @Test
    void menuService_reportsClosedMenus_andBackAtTheRoot() {
        OpenMenuContextRegistry contexts = new OpenMenuContextRegistry();
        MenuSessionRegistry sessions = new MenuSessionRegistry();
        MenuService service = service(contexts, sessions);
        Recorder recorder = new Recorder();
        service.addObserver(recorder);
        assertSame(service.observers(), service.observers());

        OpenMenuContext open = mock(OpenMenuContext.class);
        RuntimeMenu menu = mock(RuntimeMenu.class);
        when(menu.key()).thenReturn("leaderboards.main");
        when(open.menu()).thenReturn(menu);
        contexts.register(ID, open);
        service.onMenuInventoryClosed(ID);

        // menu.back with nothing to go back to closes the inventory: no back event.
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(ID);
        sessions.open(ID).openAsRoot("profile.main", MenuContextParams.EMPTY, "Profile");
        service.goBack(player);
        verify(player).closeInventory();

        assertEquals(List.of("closed leaderboards.main"), recorder.calls);
    }

    @Test
    void menuService_withoutObservers_looksNothingUp() {
        OpenMenuContextRegistry contexts = mock(OpenMenuContextRegistry.class);
        service(contexts, new MenuSessionRegistry()).onMenuInventoryClosed(ID);
        org.mockito.Mockito.verifyNoInteractions(contexts);
    }
}

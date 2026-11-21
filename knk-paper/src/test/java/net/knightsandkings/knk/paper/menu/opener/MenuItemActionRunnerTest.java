package net.knightsandkings.knk.paper.menu.opener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.menu.opener.MenuItemAction;

/** Siege shop DESIGN § 4.3.2 (PandiO/knk-workspace#871): what each action does for the player. */
class MenuItemActionRunnerTest {

    private final Player player = mock(Player.class);
    private final List<String> openedByKey = new ArrayList<>();
    private boolean available = true;
    private final MenuItemActionRunner runner = new MenuItemActionRunner((p, key) -> {
        if (!available) return false;
        openedByKey.add(key);
        return true;
    });

    @Test
    void theMainMenuOpensThroughSlashMenuAsBefore() {
        runner.accept(player, MenuItemAction.openMenu("main"));
        verify(player).performCommand("menu");
        assertTrue(openedByKey.isEmpty());
    }

    @Test
    void theSiegeMenusOpenThroughTheirCommands_WhichKnowTheMatch() {
        runner.accept(player, MenuItemAction.openMenu("siege.information"));
        verify(player).performCommand("siege");
        runner.accept(player, MenuItemAction.openMenu("siege.shop"));
        verify(player).performCommand("siege shop");
    }

    @Test
    void anyOtherMenuOpensByKey_AndAnUnavailableOneSaysSo() {
        runner.accept(player, MenuItemAction.openMenu("siege.unlocks"));
        assertEquals(List.of("siege.unlocks"), openedByKey);
        available = false;
        runner.accept(player, MenuItemAction.openMenu("siege.overview"));
        verify(player).sendMessage(org.mockito.ArgumentMatchers.contains(MenuItemActionRunner.UNAVAILABLE));
    }

    @Test
    void aCommandIsRunByThePlayer_AndNoneDoesNothing() {
        runner.accept(player, MenuItemAction.runCommand("/warp hub"));
        verify(player).performCommand("warp hub");
        runner.accept(player, MenuItemAction.none());
        verify(player, never()).performCommand("menu");
        verify(player, never()).sendMessage(anyString());
    }

    @Test
    void aFeatureCanRouteItsOwnKey() {
        List<Player> routed = new ArrayList<>();
        runner.route("arena", routed::add);
        runner.accept(player, MenuItemAction.openMenu("arena"));
        assertEquals(List.of(player), routed);
    }
}

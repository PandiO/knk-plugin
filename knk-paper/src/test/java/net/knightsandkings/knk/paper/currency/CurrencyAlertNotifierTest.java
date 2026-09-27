package net.knightsandkings.knk.paper.currency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.currency.CurrencyAlertNotice;
import net.knightsandkings.knk.core.domain.users.PlayerNotification;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** KNG-21 Phase 5: a CurrencyAlert notification reaches online staff with knk.admin.currency.alerts only. */
class CurrencyAlertNotifierTest {

    private final List<String> staffSees = new ArrayList<>();
    private final List<String> playerSees = new ArrayList<>();
    private final Player staff = player("mod", staffSees);
    private final Player regular = player("steve", playerSees);
    private final Map<Player, Boolean> hasNode = Map.of(staff, true, regular, false);

    private final CurrencyAlertNotifier notifier = new CurrencyAlertNotifier(CurrencySettings.defaults(),
        (player, node) -> CompletableFuture.completedFuture(net.knightsandkings.knk.core.domain.permissions.PermissionDecision.of(
            PlayerCurrencyService.CURRENCY_ALERTS_NODE.equals(node) && hasNode.getOrDefault(player, false))),
        () -> (Collection<Player>) List.of(staff, regular), Runnable::run);

    private static Player player(String name, List<String> into) {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn(name);
        when(player.isOnline()).thenReturn(true);
        org.mockito.Mockito.doAnswer(inv -> into.add(inv.getArgument(0))).when(player).sendMessage(anyString());
        org.mockito.Mockito.doAnswer(inv -> into.add(PlainTextComponentSerializer.plainText().serialize(inv.getArgument(0))))
            .when(player).sendMessage(any(Component.class));
        return player;
    }

    private static PlayerNotification notification(List<String> transfersDisabled) {
        return new PlayerNotification(3, 0, null, "", PlayerNotification.TYPE_CURRENCY_ALERT, null, null,
            new CurrencyAlertNotice(12, "R1", "Reconciliation mismatch", "Critical", "Reconciliation found 1 balance mismatch", 5, "bob",
                transfersDisabled));
    }

    @Test
    void onlyStaffWithTheNodeSeeTheAlert() {
        notifier.handle(notification(List.of()));

        assertEquals(List.of("[Currency alert] Critical R1 (Reconciliation mismatch): bob: Reconciliation found 1 balance mismatch"), staffSees);
        assertTrue(playerSees.isEmpty());
    }

    @Test
    void aKillSwitchAlert_saysWhichTransfersWereSwitchedOff() {
        notifier.handle(notification(List.of("Coins")));

        assertEquals(2, staffSees.size());
        assertTrue(staffSees.get(1).contains("Coins transfers were switched off"), staffSees.get(1));
    }

    @Test
    void aNotificationWithoutItsPayload_isIgnored() {
        notifier.handle(new PlayerNotification(4, 0, null, "", PlayerNotification.TYPE_CURRENCY_ALERT, null));

        assertTrue(staffSees.isEmpty());
    }
}

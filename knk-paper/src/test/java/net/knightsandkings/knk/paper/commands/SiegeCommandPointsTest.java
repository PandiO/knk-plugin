package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.paper.siege.SiegeService;
import net.knightsandkings.knk.paper.siege.points.SiegePointsService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code /siege points} and {@code /siege pay} (siege shop L1, PandiO/knk-workspace#866): with siege points off they
 * are neither suggested nor in the help and only say the feature is off (A12); with them on they reach the service.
 */
class SiegeCommandPointsTest {

    private final SiegeService service = mock(SiegeService.class);
    private final Player player = mock(Player.class);
    private final Command command = mock(Command.class);

    SiegeCommandPointsTest() {
        when(player.getUniqueId()).thenReturn(new UUID(0, 1));
        when(service.hasPermission(any(Player.class), anyString())).thenReturn(true);
    }

    private String lastMessage() {
        ArgumentCaptor<Component> message = ArgumentCaptor.forClass(Component.class);
        verify(player, org.mockito.Mockito.atLeastOnce()).sendMessage(message.capture());
        return PlainTextComponentSerializer.plainText().serialize(message.getValue());
    }

    @Test
    void withSiegePointsOffTheSubcommandsAreHiddenAndSaySo_A12() {
        SiegeCommand siege = new SiegeCommand(service);
        List<String> suggestions = siege.onTabComplete(player, command, "siege", new String[] {""});
        assertFalse(suggestions.contains("points"));
        assertFalse(suggestions.contains("pay"));

        siege.onCommand(player, command, "siege", new String[] {"points"});
        assertTrue(lastMessage().endsWith("Siege points are not enabled on this server."));

        siege.onCommand(player, command, "siege", new String[] {"help"});
        assertFalse(lastMessage().contains("points"));
    }

    @Test
    void withSiegePointsOnTheSubcommandsReachTheService() {
        SiegePointsService points = mock(SiegePointsService.class);
        when(points.points(player)).thenReturn(Component.text("Siege points: 100"));
        when(points.pay(player, "Anna", "50")).thenReturn(Component.text("Sent 50"));
        SiegeCommand siege = new SiegeCommand(service);
        siege.setPoints(points);

        assertTrue(siege.onTabComplete(player, command, "siege", new String[] {"p"}).containsAll(List.of("points", "pay")));
        siege.onCommand(player, command, "siege", new String[] {"points"});
        assertTrue(lastMessage().equals("Siege points: 100"));
        siege.onCommand(player, command, "siege", new String[] {"pay", "Anna", "50"});
        assertTrue(lastMessage().equals("Sent 50"));
        siege.onCommand(player, command, "siege", new String[] {"help"});
        assertTrue(lastMessage().contains("points | pay <player> <amount>"));
    }
}

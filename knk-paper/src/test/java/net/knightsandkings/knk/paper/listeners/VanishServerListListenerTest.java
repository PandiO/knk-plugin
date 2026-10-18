package net.knightsandkings.knk.paper.listeners;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.bukkit.entity.Player;
import org.bukkit.event.server.ServerListPingEvent;
import org.junit.jupiter.api.Test;

import net.kyori.adventure.text.Component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * Players in owner/staff mode are left out of the server-list sample and online count.
 */
class VanishServerListListenerTest {

    private static final InetSocketAddress LOOPBACK = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);

    private final Player visible = mock(Player.class);
    private final Player staff = mock(Player.class);
    private final Player owner = mock(Player.class);

    @Test
    void vanishedPlayersAreRemovedFromTheListAndCount() {
        ListedPing ping = new ListedPing(List.of(visible, staff, owner));
        new VanishServerListListener(Set.of(staff, owner)::contains).onServerListPing(ping);

        assertEquals(List.of(visible), ping.players);
        assertEquals(1, ping.getNumPlayers());
    }

    @Test
    void nobodyVanishedLeavesThePingAlone() {
        ListedPing ping = new ListedPing(List.of(visible, staff));
        new VanishServerListListener(player -> false).onServerListPing(ping);

        assertEquals(List.of(visible, staff), ping.players);
        assertEquals(2, ping.getNumPlayers());
    }

    @Test
    void aPingWithoutAPlayerListIsIgnored() {
        // Bukkit's base event throws on iterator(); the listener must not fail the ping.
        ServerListPingEvent ping = new ServerListPingEvent("", LOOPBACK, Component.text("motd"), 3, 20);
        new VanishServerListListener(player -> true).onServerListPing(ping);

        assertEquals(3, ping.getNumPlayers());
    }

    /** Stands in for Paper's event: removing through the iterator also lowers the count. */
    private static final class ListedPing extends ServerListPingEvent {
        final List<Player> players;

        ListedPing(List<Player> players) {
            super("", LOOPBACK, Component.text("motd"), players.size(), 20);
            this.players = new ArrayList<>(players);
        }

        @Override
        public int getNumPlayers() {
            return players.size();
        }

        @Override
        public Iterator<Player> iterator() {
            return players.iterator();
        }
    }
}

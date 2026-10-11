package net.knightsandkings.knk.paper.listeners;

import java.util.Iterator;
import java.util.Objects;
import java.util.function.Predicate;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerListPingEvent;

/**
 * Keeps players in owner/staff mode out of the multiplayer server list: they are left out of the
 * hover sample of online names and out of the online count, matching how they are already hidden
 * in game and in the tab list ({@link net.knightsandkings.knk.paper.modes.ModeService}).
 * <p>
 * A ping has no viewer, so vanished players are hidden from everyone here, staff included.
 * Removing through the event's iterator drops the player from both the sample and
 * {@link ServerListPingEvent#getNumPlayers()} (Paper's {@code PaperServerListPingEvent}). Runs at
 * LOW, before {@link GameSettingsMotdListener} reads the count for the MOTD's {@code {online}}.
 * Pings can arrive off the main thread; the vanished check only reads a concurrent map.
 */
public class VanishServerListListener implements Listener {

    private final Predicate<Player> isVanished;

    public VanishServerListListener(Predicate<Player> isVanished) {
        this.isVanished = Objects.requireNonNull(isVanished, "isVanished must not be null");
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onServerListPing(ServerListPingEvent event) {
        Iterator<Player> players;
        try {
            players = event.iterator();
        } catch (UnsupportedOperationException e) {
            return; // a ping event that doesn't list players (not Paper's)
        }
        while (players.hasNext()) {
            if (isVanished.test(players.next())) {
                players.remove();
            }
        }
    }
}

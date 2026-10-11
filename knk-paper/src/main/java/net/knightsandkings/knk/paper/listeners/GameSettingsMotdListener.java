package net.knightsandkings.knk.paper.listeners;

import java.util.Objects;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerListPingEvent;

import net.knightsandkings.knk.paper.settings.GameSettingsManager;

/**
 * The server-list MOTD from the Game Settings page (docs/specs/game-settings/DESIGN.md §3.9, KNG-52):
 * {@code &}-coded, two lines, {@code {online}}/{@code {max}} filled in. No MOTD set (or no settings
 * read yet) leaves server.properties' motd.
 */
public class GameSettingsMotdListener implements Listener {

    private final GameSettingsManager settings;

    public GameSettingsMotdListener(GameSettingsManager settings) {
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onServerListPing(ServerListPingEvent event) {
        settings.motd(event.getNumPlayers(), event.getMaxPlayers()).ifPresent(event::motd);
    }
}

package net.knightsandkings.knk.paper.statistics;

import java.util.UUID;
import java.util.function.Function;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import net.knightsandkings.knk.paper.events.UserDataLoadedEvent;

/**
 * Statistics sessions (DESIGN.md §F.3): a session starts when the player's account data has loaded
 * ({@link UserDataLoadedEvent} - the first moment the plugin knows the user id; without one the
 * session is tracked under the UUID and resolved later) and ends at quit, as a kick when a kick
 * event came first. Reconnects are new sessions (and logins). Server stop is handled by
 * {@link StatisticsFlushTask#shutdown}.
 */
public final class StatisticsSessionListener implements Listener {

    private final StatisticsService service;

    public StatisticsSessionListener(StatisticsService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUserDataLoaded(UserDataLoadedEvent event) {
        Player player = event.getPlayer();
        if (player != null && player.isOnline()) {
            service.sessionStarted(player, event.getUserId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {
        service.kicked(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        service.sessionEnded(event.getPlayer());
    }

    /**
     * After a plugin (re)load with players online: their sessions start now with the cached user id
     * (null → resolved later by UUID).
     */
    public void startOnlinePlayers(Function<UUID, Integer> cachedUserId) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!service.hasSession(player.getUniqueId())) {
                service.sessionStarted(player, cachedUserId.apply(player.getUniqueId()));
            }
        }
    }
}

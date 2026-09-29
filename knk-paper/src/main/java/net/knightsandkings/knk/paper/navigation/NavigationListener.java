package net.knightsandkings.knk.paper.navigation;

import java.util.Objects;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import net.knightsandkings.knk.core.navigation.NavigationEffect.EndReason;

/**
 * Ends navigation sessions on quit, death, world change and teleports of more than 16 blocks
 * (DESIGN §6.4). Siege joins and the session timeout are the service's own checks.
 */
public final class NavigationListener implements Listener {

    private final NavigationService service;

    public NavigationListener(NavigationService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        service.end(event.getPlayer(), EndReason.QUIT);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        service.end(event.getEntity(), EndReason.DEATH);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        service.end(event.getPlayer(), EndReason.WORLD_CHANGE);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo() == null) {
            return;
        }
        service.onTeleport(event.getPlayer(), event.getFrom(), event.getTo());
    }
}

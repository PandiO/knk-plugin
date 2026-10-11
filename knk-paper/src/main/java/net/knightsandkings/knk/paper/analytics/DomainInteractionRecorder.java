package net.knightsandkings.knk.paper.analytics;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import net.knightsandkings.knk.core.analytics.WorldAnalyticsWindow;
import net.knightsandkings.knk.core.domain.discovery.DiscoveryGrant;
import net.knightsandkings.knk.core.domain.discovery.DiscoveryGrantResult;
import net.knightsandkings.knk.paper.events.OnRegionEnterEvent;
import net.knightsandkings.knk.paper.events.OnRegionLeaveEvent;

/**
 * Domain interactions (KNG-34 link 7, DESIGN.md D11): WorldGuard region entries/exits from
 * {@code WorldGuardRegionTracker}'s {@link OnRegionEnterEvent}/{@link OnRegionLeaveEvent} (by region id;
 * the API keeps the regions that are domains) and discovery grants (by domain id, via
 * {@code DiscoveryEffects.addGrantObserver}). The window counts them and the day's distinct players
 * (in memory only). Main thread.
 */
public final class DomainInteractionRecorder implements Listener {

    private final WorldAnalyticsWindow window;

    public DomainInteractionRecorder(WorldAnalyticsWindow window) {
        this.window = window;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRegionEnter(OnRegionEnterEvent event) {
        Player player = event.getPlayer();
        window.regionEntered(event.getRegionId(), player == null ? null : player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRegionLeave(OnRegionLeaveEvent event) {
        Player player = event.getPlayer();
        window.regionLeft(event.getRegionId(), player == null ? null : player.getUniqueId());
    }

    /** Every domain the player was just granted (late replays included: they are discoveries of today). */
    public void discoveriesGranted(Player player, DiscoveryGrantResult result) {
        if (result == null) {
            return;
        }
        for (DiscoveryGrant grant : result.granted()) {
            window.domainDiscovered(grant.domainId(), player == null ? null : player.getUniqueId());
        }
    }
}

package net.knightsandkings.knk.paper.analytics;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import net.knightsandkings.knk.core.analytics.WorldAnalyticsWindow;
import net.knightsandkings.knk.core.domain.discovery.DiscoveryGrant;
import net.knightsandkings.knk.core.domain.discovery.DiscoveryGrantResult;
import net.knightsandkings.knk.paper.events.OnDomainEnterEvent;
import net.knightsandkings.knk.paper.events.OnDomainLeaveEvent;

/**
 * Domain interactions (KNG-34 link 7, DESIGN.md D11): domain entries/exits from
 * {@code WorldGuardRegionTracker}'s {@link OnDomainEnterEvent}/{@link OnDomainLeaveEvent} (KNG-256: once per domain,
 * so moving between a domain's main region and its sub-regions counts nothing; sent by the domain's main region id,
 * which the API resolves) and discovery grants (by domain id, via
 * {@code DiscoveryEffects.addGrantObserver}). The window counts them and the day's distinct players
 * (in memory only). Main thread.
 */
public final class DomainInteractionRecorder implements Listener {

    private final WorldAnalyticsWindow window;

    public DomainInteractionRecorder(WorldAnalyticsWindow window) {
        this.window = window;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDomainEnter(OnDomainEnterEvent event) {
        Player player = event.getPlayer();
        if (event.getDomain().wgRegionId() != null) {
            window.regionEntered(event.getDomain().wgRegionId(), player == null ? null : player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDomainLeave(OnDomainLeaveEvent event) {
        Player player = event.getPlayer();
        if (event.getDomain().wgRegionId() != null) {
            window.regionLeft(event.getDomain().wgRegionId(), player == null ? null : player.getUniqueId());
        }
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

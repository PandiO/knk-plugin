package net.knightsandkings.knk.paper.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import net.knightsandkings.knk.core.regions.RegionDomainResolver.DomainSnapshot;

/**
 * A player leaves a domain (KNG-256), fired by {@code WorldGuardRegionTracker} after the move. Unlike
 * {@link OnRegionLeaveEvent}, which fires per WorldGuard region, this fires once per domain: moving between a domain's
 * main region and its sub-regions fires nothing. Only domains already known to the region resolver count.
 */
public class OnDomainLeaveEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final DomainSnapshot domain;

    public OnDomainLeaveEvent(Player player, DomainSnapshot domain) {
        this.player = player;
        this.domain = domain;
    }

    public Player getPlayer() {
        return player;
    }

    public DomainSnapshot getDomain() {
        return domain;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}

package net.knightsandkings.knk.paper.events;

import net.knightsandkings.knk.core.navigation.NavigationEffect.RouteReason;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired when a navigating player's route was recomputed and adopted (road navigation DESIGN §6.6):
 * they left the road, an element on the route closed, or something opened that made the route
 * shorter. Informational; not cancellable.
 */
public class NavigationRerouteEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String destination;
    private final RouteReason reason;
    private final String detail;

    public NavigationRerouteEvent(Player player, String destination, RouteReason reason, String detail) {
        this.player = player;
        this.destination = destination;
        this.reason = reason;
        this.detail = detail;
    }

    public Player getPlayer() {
        return player;
    }

    public String getDestination() {
        return destination;
    }

    /** Why the route was recomputed. */
    public RouteReason getReason() {
        return reason;
    }

    /** The element that caused it ("the West Gate is closed"), or null. */
    public String getDetail() {
        return detail;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}

package net.knightsandkings.knk.paper.events;

import net.knightsandkings.knk.core.navigation.NavigationEffect.EndReason;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired when a navigation session ends without arriving (road navigation DESIGN §6.6):
 * {@code /navigate stop}, no route, quit, death, world change, a teleport of more than 16 blocks,
 * joining a siege, or the session timeout. Arrivals fire {@link NavigationArriveEvent} instead.
 */
public class NavigationEndEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String destination;
    private final EndReason reason;

    public NavigationEndEvent(Player player, String destination, EndReason reason) {
        this.player = player;
        this.destination = destination;
        this.reason = reason;
    }

    public Player getPlayer() {
        return player;
    }

    public String getDestination() {
        return destination;
    }

    public EndReason getReason() {
        return reason;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}

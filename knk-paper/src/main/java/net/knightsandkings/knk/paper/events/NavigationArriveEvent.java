package net.knightsandkings.knk.paper.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired when a navigating player reaches their destination (road navigation DESIGN §6.4/§6.6):
 * within {@code arrive-distance} of the goal, or inside the destination region. A partial route
 * ("Guiding you to the gate") does not arrive: it ends at the gate with {@link NavigationEndEvent}
 * once the player stops, or continues after the gate opens.
 */
public class NavigationArriveEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String destination;

    public NavigationArriveEvent(Player player, String destination) {
        this.player = player;
        this.destination = destination;
    }

    public Player getPlayer() {
        return player;
    }

    public String getDestination() {
        return destination;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}

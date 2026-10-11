package net.knightsandkings.knk.paper.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired when a player starts {@code /navigate} to a destination, before the first route is computed
 * (road navigation DESIGN §6.6). Cancel it to refuse the navigation silently (the canceller tells the
 * player why). Later consumers: transport quests, tutorial steps, bandit ambushes.
 */
public class NavigationStartEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String destination;
    private boolean cancelled;

    public NavigationStartEvent(Player player, String destination) {
        this.player = player;
        this.destination = destination;
    }

    public Player getPlayer() {
        return player;
    }

    /** The destination's display name ("Kardenna Market"). */
    public String getDestination() {
        return destination;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}

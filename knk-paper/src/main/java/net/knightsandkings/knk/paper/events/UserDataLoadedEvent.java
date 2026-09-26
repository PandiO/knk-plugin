package net.knightsandkings.knk.paper.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import net.knightsandkings.knk.paper.user.PlayerUserData;

/**
 * Fired on the main thread once a joining player's account data has loaded and the join-loading
 * hold ({@link net.knightsandkings.knk.paper.user.JoinLoadingGuard}) is released - the first moment
 * the plugin knows who the player is. Fired after the welcome messages. {@link #getUserData()} can
 * be a fallback entry without a user id when the API couldn't be reached.
 */
public class UserDataLoadedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final PlayerUserData userData;

    public UserDataLoadedEvent(Player player, PlayerUserData userData) {
        this.player = player;
        this.userData = userData;
    }

    public Player getPlayer() {
        return player;
    }

    public PlayerUserData getUserData() {
        return userData;
    }

    /** The knk user id, or null when the account couldn't be loaded. */
    public Integer getUserId() {
        return userData == null ? null : userData.userId();
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}

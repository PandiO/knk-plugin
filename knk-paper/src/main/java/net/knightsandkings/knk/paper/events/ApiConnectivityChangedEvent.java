package net.knightsandkings.knk.paper.events;

import net.knightsandkings.knk.core.connectivity.ApiConnectivityState;
import net.knightsandkings.knk.core.connectivity.ApiConnectivityTransition;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired on the main server thread each time the plugin's service-wide view of knk-web-api changes
 * state (KNG-115, see {@code ApiConnectivityMonitor}). Never fired for a probe that keeps the
 * state. The first transition after enable comes from {@link ApiConnectivityState#UNKNOWN}; a
 * feature that wants "the API came back" should check {@link #isRecovery()}.
 */
public class ApiConnectivityChangedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final ApiConnectivityTransition transition;

    public ApiConnectivityChangedEvent(ApiConnectivityTransition transition) {
        super(false);
        this.transition = transition;
    }

    public ApiConnectivityTransition getTransition() {
        return transition;
    }

    public ApiConnectivityState getPreviousState() {
        return transition.from();
    }

    public ApiConnectivityState getNewState() {
        return transition.to();
    }

    /** DOWN to UP: the API is reachable again after an outage. */
    public boolean isRecovery() {
        return transition.isRecovery();
    }

    /** Now DOWN (from UP, or from UNKNOWN when the server started without the API). */
    public boolean isOutage() {
        return transition.isOutage();
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}

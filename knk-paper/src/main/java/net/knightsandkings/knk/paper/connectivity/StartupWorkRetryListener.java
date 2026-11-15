package net.knightsandkings.knk.paper.connectivity;

import net.knightsandkings.knk.core.realtime.StartupWorkRetry;
import net.knightsandkings.knk.paper.events.ApiConnectivityChangedEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Objects;

/**
 * PandiO/knk-workspace#31: hands every KNG-115 connectivity transition to {@link StartupWorkRetry}, so startup-only API
 * work that failed at start (the siege's unfinished-match abort, stale gate restore, runtime config and title brackets)
 * runs again once the API is UP, with or without the realtime hub. Any transition into UP counts, UNKNOWN to UP included
 * (not only {@link ApiConnectivityChangedEvent#isRecovery()}). MONITOR priority: the data access already sees the new
 * state (the monitor updates it before the event), and other recovery handlers ran first.
 */
public final class StartupWorkRetryListener implements Listener {

    private final StartupWorkRetry retry;

    public StartupWorkRetryListener(StartupWorkRetry retry) {
        this.retry = Objects.requireNonNull(retry, "retry");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onConnectivity(ApiConnectivityChangedEvent event) {
        retry.connectivityChanged(event.getNewState());
    }
}

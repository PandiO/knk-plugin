package net.knightsandkings.knk.paper.connectivity;

import net.knightsandkings.knk.core.connectivity.ApiConnectivityState;
import net.knightsandkings.knk.core.connectivity.ApiConnectivityTransition;
import net.knightsandkings.knk.core.realtime.PendingStartupWork;
import net.knightsandkings.knk.core.realtime.StartupWorkRetry;
import net.knightsandkings.knk.paper.events.ApiConnectivityChangedEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** PandiO/knk-workspace#31: the KNG-115 connectivity event drives the siege startup retry, UNKNOWN to UP included. */
class StartupWorkRetryListenerTest {

    private static ApiConnectivityChangedEvent event(ApiConnectivityState from, ApiConnectivityState to) {
        return new ApiConnectivityChangedEvent(new ApiConnectivityTransition(from, to, Instant.EPOCH, "probe"));
    }

    @Test
    void everyTransitionIntoUp_runsThePendingWork_downDoesNot() {
        PendingStartupWork work = new PendingStartupWork();
        List<String> runs = new ArrayList<>();
        boolean[] ok = {false};
        work.register("siege config and title brackets", () -> {
            runs.add("config");
            return CompletableFuture.completedFuture(ok[0]);
        });
        ApiConnectivityState[] state = {ApiConnectivityState.UNKNOWN};
        StartupWorkRetry retry = new StartupWorkRetry(work, () -> state[0], (delay, task) -> { },
            StartupWorkRetry.DEFAULT_DELAYS, "[Siege] Startup work", Logger.getLogger("test"));
        StartupWorkRetryListener listener = new StartupWorkRetryListener(retry);

        state[0] = ApiConnectivityState.DOWN;
        listener.onConnectivity(event(ApiConnectivityState.UNKNOWN, ApiConnectivityState.DOWN));
        assertEquals(List.of(), runs);

        state[0] = ApiConnectivityState.UP;
        listener.onConnectivity(event(ApiConnectivityState.UNKNOWN, ApiConnectivityState.UP)); // not isRecovery()
        assertEquals(List.of("config"), runs);

        ok[0] = true;
        listener.onConnectivity(event(ApiConnectivityState.DOWN, ApiConnectivityState.UP));
        listener.onConnectivity(event(ApiConnectivityState.DOWN, ApiConnectivityState.UP));
        assertEquals(List.of("config", "config"), runs, "once it got through it is not run again");
    }
}

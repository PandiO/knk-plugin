package net.knightsandkings.knk.paper.connectivity;

import net.knightsandkings.knk.core.connectivity.ApiConnectivity;
import net.knightsandkings.knk.core.connectivity.ApiConnectivityState;
import net.knightsandkings.knk.core.connectivity.ApiConnectivityTransition;
import net.knightsandkings.knk.core.domain.HealthStatus;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.HealthApi;
import net.knightsandkings.knk.paper.events.ApiConnectivityChangedEvent;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Drives {@link ApiConnectivity} (KNG-115): probes knk-web-api's {@code /health/ready} on an async
 * Bukkit timer, feeds each result into the state machine and fires
 * {@link ApiConnectivityChangedEvent} on the main thread when the state changes.
 * <p>
 * The probe always calls {@link HealthApi} directly, never a cache, so a cached "healthy" can't
 * stand in for "reachable now". A probe that is still running when the next tick comes is not
 * overlapped; that tick is skipped.
 */
public final class ApiConnectivityMonitor {

    private final HealthApi healthApi;
    private final ApiConnectivity connectivity;
    private final Executor mainThread;
    private final Consumer<ApiConnectivityTransition> publisher;
    private final Clock clock;
    private final AtomicBoolean probeInFlight = new AtomicBoolean(false);
    private volatile BukkitTask task;

    public ApiConnectivityMonitor(
        HealthApi healthApi,
        ApiConnectivity connectivity,
        Executor mainThread,
        Consumer<ApiConnectivityTransition> publisher,
        Clock clock
    ) {
        this.healthApi = healthApi;
        this.connectivity = connectivity;
        this.mainThread = mainThread;
        this.publisher = publisher;
        this.clock = clock;
    }

    /**
     * Builds the monitor for the running plugin and starts probing (first probe right away).
     * Returns null when {@code api.connectivity.enabled} is false.
     */
    public static ApiConnectivityMonitor start(Plugin plugin, HealthApi healthApi, ApiConnectivitySettings settings) {
        if (!settings.enabled()) {
            plugin.getLogger().warning("API connectivity probe disabled (api.connectivity.enabled=false); "
                + "connectivity stays UNKNOWN");
            return null;
        }
        Clock clock = Clock.systemUTC();
        ApiConnectivity connectivity = new ApiConnectivity(settings.failuresToDown(), settings.successesToUp(), clock.instant());
        Logger log = plugin.getLogger();
        ApiConnectivityMonitor monitor = new ApiConnectivityMonitor(
            healthApi,
            connectivity,
            runnable -> {
                if (plugin.isEnabled()) {
                    Bukkit.getScheduler().runTask(plugin, runnable);
                }
            },
            transition -> {
                String line = "API connectivity " + transition.from() + " -> " + transition.to()
                    + " (" + transition.reason() + ")";
                if (transition.to() == ApiConnectivityState.DOWN) {
                    log.warning(line);
                } else {
                    log.info(line);
                }
                Bukkit.getPluginManager().callEvent(new ApiConnectivityChangedEvent(transition));
            },
            clock
        );
        long periodTicks = Math.max(20L, settings.probeInterval().toSeconds() * 20L);
        monitor.task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, monitor::probe, 0L, periodTicks);
        log.info("API connectivity probe every " + settings.probeInterval().toSeconds() + "s (DOWN after "
            + settings.failuresToDown() + " failures, UP after " + settings.successesToUp() + " successes)");
        return monitor;
    }

    public void stop() {
        BukkitTask t = task;
        task = null;
        if (t != null) {
            t.cancel();
        }
    }

    public ApiConnectivity connectivity() {
        return connectivity;
    }

    /** Runs one probe unless the previous one hasn't finished. Safe from any thread. */
    public void probe() {
        if (!probeInFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            healthApi.getHealth().whenComplete((health, error) -> {
                try {
                    onResult(health, error);
                } finally {
                    probeInFlight.set(false);
                }
            });
        } catch (RuntimeException e) {
            // e.g. the client's executor already shut down during disable
            try {
                onResult(null, e);
            } finally {
                probeInFlight.set(false);
            }
        }
    }

    /** Feeds one probe outcome into the state machine; publishes a transition on the main thread. */
    void onResult(HealthStatus health, Throwable error) {
        Optional<ApiConnectivityTransition> transition;
        if (error == null && health != null && health.isHealthy()) {
            transition = connectivity.recordSuccess(clock.instant(), health.status());
        } else {
            transition = connectivity.recordFailure(clock.instant(), describeFailure(health, error));
        }
        transition.ifPresent(t -> mainThread.execute(() -> publisher.accept(t)));
    }

    static String describeFailure(HealthStatus health, Throwable error) {
        if (error == null) {
            return health == null ? "no response" : health.status();
        }
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof ApiException apiEx) {
            if (apiEx.getStatusCode() > 0) {
                return "HTTP " + apiEx.getStatusCode();
            }
            if (apiEx.getCause() != null) {
                cause = apiEx.getCause();
            }
        }
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }
}

package net.knightsandkings.knk.paper.analytics;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import net.knightsandkings.knk.core.analytics.WorldAnalyticsWindow;
import net.knightsandkings.knk.core.domain.analytics.WorldAnalyticsBatch;
import net.knightsandkings.knk.core.domain.statistics.StatisticsCatalog;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.WorldAnalyticsApi;

/**
 * Posts the world-analytics windows (KNG-34 link 7, IMPLEMENTATION_PLAN.md §3.4/§7: one aggregate batch
 * per {@code flush-interval-seconds}, default 5 min). Runs <b>off the main thread</b>. Every closed window
 * is one batch with its own id (the API applies an id once, so a retry never double counts):
 * <ul>
 *   <li>answered (applied or duplicate): done;</li>
 *   <li>refused for good - 400 (invalid/too old), 404, 413, 422 - or 503 (the API's
 *   {@code WorldAnalytics:Enabled = false}): dropped and logged once;</li>
 *   <li>anything else (API down, 5xx, 401/403 key mismatch): back into the window's bounded in-memory
 *   queue, retried on the next flush. Never spooled to disk.</li>
 * </ul>
 * On start it reads {@code GET api/statistics/catalog}'s {@code timeZone} so windows close at the API's
 * local midnight (until it answers, the configured default zone is used).
 */
public final class WorldAnalyticsFlushTask {

    private static final Logger LOGGER = Logger.getLogger(WorldAnalyticsFlushTask.class.getName());
    static final long SHUTDOWN_WAIT_MILLIS = 2_000L;

    private final WorldAnalyticsWindow window;
    private final WorldAnalyticsApi api;
    private final String serverName;
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private volatile boolean apiDownLogged;
    private volatile boolean refusedLogged;
    private BukkitTask flushTimer;

    public WorldAnalyticsFlushTask(WorldAnalyticsWindow window, WorldAnalyticsApi api, String serverName) {
        this.window = window;
        this.api = api;
        this.serverName = serverName;
    }

    public void start(Plugin plugin, int flushIntervalSeconds, Supplier<CompletableFuture<StatisticsCatalog>> catalog) {
        long ticks = Math.max(1, flushIntervalSeconds) * 20L;
        flushTimer = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> flush(), ticks, ticks);
        if (catalog != null) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> readTimeZone(catalog));
        }
    }

    /** Applies the catalogue's time zone; a failure keeps the default (logged once). */
    CompletableFuture<Void> readTimeZone(Supplier<CompletableFuture<StatisticsCatalog>> catalog) {
        try {
            return catalog.get().handle((answer, error) -> {
                if (error != null || answer == null || answer.timeZone() == null || answer.timeZone().isBlank()) {
                    LOGGER.info("[WorldAnalytics] Could not read the statistics time zone; using " + window.zone());
                    return null;
                }
                try {
                    window.setZone(ZoneId.of(answer.timeZone()));
                } catch (DateTimeException e) {
                    LOGGER.warning("[WorldAnalytics] Unknown statistics time zone '" + answer.timeZone() + "'; using " + window.zone());
                }
                return null;
            });
        } catch (RuntimeException e) {
            LOGGER.info("[WorldAnalytics] Could not read the statistics time zone: " + e);
            return CompletableFuture.completedFuture(null);
        }
    }

    /** Stops the timer and posts what was aggregated with a bounded wait (plugin disable). */
    public void shutdown() {
        if (flushTimer != null) {
            flushTimer.cancel();
        }
        try {
            flush().get(SHUTDOWN_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            LOGGER.fine("[WorldAnalytics] Final flush did not finish: " + e);
        }
    }

    /** One flush; the future completes when every batch was answered or failed. Skipped while one is in flight. */
    public CompletableFuture<Void> flush() {
        if (!inFlight.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }
        List<WorldAnalyticsBatch> batches;
        try {
            batches = window.drain();
        } catch (RuntimeException e) {
            inFlight.set(false);
            LOGGER.warning("[WorldAnalytics] Could not close the window: " + e);
            return CompletableFuture.completedFuture(null);
        }
        if (batches.isEmpty()) {
            inFlight.set(false);
            return CompletableFuture.completedFuture(null);
        }
        List<WorldAnalyticsBatch> retry = Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<?>[] sends = new CompletableFuture<?>[batches.size()];
        for (int i = 0; i < batches.size(); i++) {
            WorldAnalyticsBatch batch = batches.get(i);
            CompletableFuture<WorldAnalyticsApi.BatchResult> call;
            try {
                call = api.postBatch(batch, serverName);
            } catch (RuntimeException e) {
                call = CompletableFuture.failedFuture(e);
            }
            sends[i] = call.handle((result, error) -> {
                if (error == null) {
                    apiDownLogged = false;
                    if (result != null && result.rejected() > 0) {
                        LOGGER.fine("[WorldAnalytics] Batch " + batch.batchId() + ": " + result.rejected() + " row(s) refused");
                    }
                } else if (isFinal(error)) {
                    if (!refusedLogged) {
                        refusedLogged = true;
                        LOGGER.warning("[WorldAnalytics] The API refused a batch; dropped (" + rootMessage(error) + ")");
                    }
                } else {
                    retry.add(batch);
                    if (!apiDownLogged) {
                        apiDownLogged = true;
                        LOGGER.info("[WorldAnalytics] API unreachable; keeping aggregates in memory (" + rootMessage(error) + ")");
                    }
                }
                return null;
            });
        }
        return CompletableFuture.allOf(sends).handle((ignored, error) -> {
            if (!retry.isEmpty()) {
                // Keep the original order (oldest first) so the bound drops the oldest.
                List<WorldAnalyticsBatch> ordered = new ArrayList<>(batches);
                ordered.retainAll(retry);
                window.requeue(ordered);
            }
            inFlight.set(false);
            return null;
        });
    }

    /** Refused for good (don't retry): invalid, too old, too large, or analytics disabled in the API. */
    static boolean isFinal(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ApiException api) {
                int status = api.getStatusCode();
                return status == 400 || status == 404 || status == 413 || status == 422 || status == 503;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}

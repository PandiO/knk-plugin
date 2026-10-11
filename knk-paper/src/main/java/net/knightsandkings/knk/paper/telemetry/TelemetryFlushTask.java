package net.knightsandkings.knk.paper.telemetry;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import net.knightsandkings.knk.core.ports.api.TelemetryApi;
import net.knightsandkings.knk.core.telemetry.TelemetryEvent;

/**
 * Sends buffered diagnostic events and keeps the emitter config fresh (KNG-34 link 6,
 * IMPLEMENTATION_PLAN.md §5.2/§7). Both timers run <b>off the main thread</b>:
 * <ul>
 *   <li>every {@code flush-interval-seconds}: drops since the last flush become one
 *   {@code telemetry.dropped} event, then up to {@value #MAX_EVENTS_PER_FLUSH} events are posted in
 *   batches of {@value #MAX_BATCH}; a failed batch is dropped and counted (never retried or spooled,
 *   L1-23); a flush is skipped while the previous one is still in flight;</li>
 *   <li>every {@code config-poll-seconds} and on start: {@code GET api/telemetry/config} (enhanced
 *   players, test runs, switch); a failed poll keeps the last config.</li>
 * </ul>
 */
public final class TelemetryFlushTask {

    private static final Logger LOGGER = Logger.getLogger(TelemetryFlushTask.class.getName());
    static final int MAX_BATCH = 500;
    static final int MAX_EVENTS_PER_FLUSH = 2_500;
    static final long SHUTDOWN_WAIT_MILLIS = 2_000L;

    private final TelemetryEmitter emitter;
    private final TelemetryApi api;
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private volatile boolean apiDownLogged;
    private BukkitTask flushTimer;
    private BukkitTask pollTimer;

    public TelemetryFlushTask(TelemetryEmitter emitter, TelemetryApi api) {
        this.emitter = emitter;
        this.api = api;
    }

    public void start(Plugin plugin, int flushIntervalSeconds, int configPollSeconds) {
        long flushTicks = Math.max(1, flushIntervalSeconds) * 20L;
        long pollTicks = Math.max(10, configPollSeconds) * 20L;
        flushTimer = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> flush(), flushTicks, flushTicks);
        pollTimer = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::pollConfig, 1L, pollTicks);
    }

    /** Stops the timers and sends what is buffered with a bounded wait (plugin disable). */
    public void shutdown() {
        for (BukkitTask task : new BukkitTask[]{flushTimer, pollTimer}) {
            if (task != null) {
                task.cancel();
            }
        }
        inFlight.set(false);
        CompletableFuture<Void> last = flush();
        try {
            last.get(SHUTDOWN_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            LOGGER.fine("[Telemetry] Final flush did not finish: " + e);
        }
    }

    /** One flush; the future completes when every batch was answered (or failed). */
    public CompletableFuture<Void> flush() {
        if (!inFlight.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            long dropped = emitter.buffer().takeUnreportedDrops();
            if (dropped > 0) {
                emitter.emitDropped(dropped, "buffer_full_or_send_failed");
            }
            CompletableFuture<Void> all = CompletableFuture.completedFuture(null);
            int sent = 0;
            while (sent < MAX_EVENTS_PER_FLUSH) {
                List<TelemetryEvent> batch = emitter.buffer().drain(MAX_BATCH);
                if (batch.isEmpty()) {
                    break;
                }
                sent += batch.size();
                all = all.thenCompose(ignored -> send(batch));
            }
            return all.whenComplete((ok, error) -> inFlight.set(false));
        } catch (RuntimeException e) {
            inFlight.set(false);
            throw e;
        }
    }

    private CompletableFuture<Void> send(List<TelemetryEvent> batch) {
        CompletableFuture<TelemetryApi.BatchResult> call;
        try {
            call = api.postBatch(batch);
        } catch (RuntimeException e) {
            call = CompletableFuture.failedFuture(e);
        }
        return call.handle((result, error) -> {
            if (error != null) {
                emitter.buffer().recordDropped(batch.size());
                if (!apiDownLogged) {
                    apiDownLogged = true;
                    LOGGER.warning("[Telemetry] Could not send diagnostic events (dropped, not retried): " + rootMessage(error));
                }
            } else {
                apiDownLogged = false;
                if (result.dropped() > 0 || result.rejected() > 0) {
                    LOGGER.fine("[Telemetry] API dropped " + result.dropped() + " and rejected " + result.rejected() + " events");
                }
            }
            return null;
        });
    }

    /** Reads the emitter config once (also on start). */
    public CompletableFuture<Void> pollConfig() {
        CompletableFuture<net.knightsandkings.knk.core.telemetry.TelemetryClientConfig> call;
        try {
            call = api.getConfig();
        } catch (RuntimeException e) {
            call = CompletableFuture.failedFuture(e);
        }
        return call.handle((config, error) -> {
            if (error == null) {
                emitter.updateConfig(config);
            } else {
                LOGGER.fine("[Telemetry] Config poll failed; keeping the last config: " + rootMessage(error));
            }
            return null;
        });
    }

    private static String rootMessage(Throwable error) {
        Throwable t = error;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
    }
}

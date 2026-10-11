package net.knightsandkings.knk.paper.statistics;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import net.knightsandkings.knk.core.domain.statistics.StatisticsBatch;
import net.knightsandkings.knk.core.statistics.StatisticsRecorder;

/**
 * Sends player statistics (IMPLEMENTATION_PLAN.md §5.2, §7):
 * <ul>
 *   <li>every {@code flush-interval-seconds} (main thread): every session's classified time and
 *   movement go to the buffer, which is drained into batches of at most {@code max-batch-entries};
 *   each batch is sent off the main thread (and spooled if the API can't take it);</li>
 *   <li>every second: the automatic AFK threshold is checked;</li>
 *   <li>every {@code replay-interval-seconds} and on start: the spool is replayed off the main thread
 *   (at most one batch per second on average), and players tracked without a user id are looked up
 *   by UUID;</li>
 *   <li>{@link #shutdown}: sessions end with {@code ServerStop}, a final flush is sent with a bounded
 *   wait, and whatever is still unsent is spooled.</li>
 * </ul>
 */
public final class StatisticsFlushTask {

    private static final Logger LOGGER = Logger.getLogger(StatisticsFlushTask.class.getName());
    /** Batches sent per flush at most; the rest waits in the buffer for the next one. */
    static final int MAX_BATCHES_PER_FLUSH = 5;
    static final long SHUTDOWN_WAIT_MILLIS = 5_000L;

    private final Plugin plugin;
    private final StatisticsService service;
    private final StatisticsRecorder recorder;
    private final Function<UUID, CompletableFuture<Optional<Integer>>> userLookup;
    private final int maxBatchEntries;
    private final long flushIntervalTicks;
    private final int replayIntervalSeconds;
    private BukkitTask flushTimer;
    private BukkitTask idleTimer;
    private BukkitTask replayTimer;

    /**
     * @param userLookup a player's knk user id by UUID: the id, empty when there is no such user, a
     *                   failed future while the API can't be reached
     */
    public StatisticsFlushTask(Plugin plugin, StatisticsService service, StatisticsRecorder recorder,
                               Function<UUID, CompletableFuture<Optional<Integer>>> userLookup) {
        this.plugin = plugin;
        this.service = service;
        this.recorder = recorder;
        this.userLookup = userLookup;
        this.maxBatchEntries = service.config().maxBatchEntries();
        this.flushIntervalTicks = Math.max(10, service.config().flushIntervalSeconds()) * 20L;
        this.replayIntervalSeconds = Math.max(10, service.config().replayIntervalSeconds());
    }

    public void start() {
        flushTimer = Bukkit.getScheduler().runTaskTimer(plugin, this::flush, flushIntervalTicks, flushIntervalTicks);
        idleTimer = Bukkit.getScheduler().runTaskTimer(plugin, () -> service.checkIdle(Bukkit::getPlayer), 20L, 20L);
        long replayTicks = replayIntervalSeconds * 20L;
        replayTimer = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            replayAsync();
            resolveUnresolved();
        }, replayTicks, replayTicks);
        replayAsync();
    }

    public void stop() {
        for (BukkitTask task : new BukkitTask[]{flushTimer, idleTimer, replayTimer}) {
            if (task != null) {
                task.cancel();
            }
        }
        flushTimer = null;
        idleTimer = null;
        replayTimer = null;
    }

    /** Main thread: accrue, drain, hand the batches to the recorder (which sends off the main thread). */
    void flush() {
        service.accrueAll();
        for (int i = 0; i < MAX_BATCHES_PER_FLUSH; i++) {
            StatisticsBatch batch = service.buffer().drain(maxBatchEntries, service.now(), UUID::randomUUID);
            if (batch == null) {
                return;
            }
            recorder.send(batch);
        }
    }

    /** Off the main thread: the spool is read from disk. */
    private void replayAsync() {
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (!recorder.spool().isEmpty()) {
                recorder.replay(replayIntervalSeconds);
            }
        });
    }

    /** Looks up the user id of every player whose session still waits for one. */
    void resolveUnresolved() {
        for (UUID playerId : service.unresolvedPlayers()) {
            CompletableFuture<Optional<Integer>> lookup;
            try {
                lookup = userLookup.apply(playerId);
            } catch (RuntimeException e) {
                continue;
            }
            if (lookup == null) {
                continue;
            }
            lookup.whenComplete((found, error) -> {
                if (error == null && found != null && found.isPresent() && found.get() > 0) {
                    runOnMainThread(() -> service.resolved(playerId, found.get()));
                } else if (error != null) {
                    LOGGER.log(Level.FINE, "[Statistics] Could not look up the user id of " + playerId, error);
                }
            });
        }
    }

    /**
     * Plugin disable (main thread, after {@link #stop()}): sessions end with {@code ServerStop}, the
     * last batches are sent with a bounded wait ({@value #SHUTDOWN_WAIT_MILLIS} ms in total) and
     * anything still unsent is spooled for the next start.
     */
    public void shutdown() {
        service.endAll(Bukkit::getPlayer);
        service.accrueAll();
        List<CompletableFuture<StatisticsRecorder.Outcome>> sends = new ArrayList<>();
        StatisticsBatch batch;
        while ((batch = service.buffer().drain(maxBatchEntries, service.now(), UUID::randomUUID)) != null) {
            sends.add(recorder.send(batch));
        }
        if (!sends.isEmpty()) {
            try {
                CompletableFuture.allOf(sends.toArray(new CompletableFuture[0])).get(SHUTDOWN_WAIT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                LOGGER.warning("[Statistics] The last statistics batches are still being sent at shutdown; spooling them");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "[Statistics] Sending the last statistics batches failed", e);
            }
        }
        recorder.spoolInFlight();
    }

    private void runOnMainThread(Runnable runnable) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }
}

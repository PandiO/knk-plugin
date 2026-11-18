package net.knightsandkings.knk.core.realtime;

import net.knightsandkings.knk.core.connectivity.ApiConnectivityState;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * When {@link PendingStartupWork} runs again without the realtime hub (PandiO/knk-workspace#31, KNG-202). Link 10 of the
 * realtime chain (#182) re-runs the pending steps on every hub (re)connect; with {@code realtime.enabled: false} (the
 * default) nothing retried them. This class is the trigger for every path, hub or not, and always goes through the same
 * {@link PendingStartupWork#runPending()} runner, so two triggers at once run each step once:
 * <ul>
 *   <li><b>API connectivity UP</b> ({@link #connectivityChanged}): any transition into UP while work is pending runs it
 *       at once, from DOWN (the API came back) and from UNKNOWN too: a startup fetch that failed during enable followed
 *       by an API that is up at the monitor's first probe gives UNKNOWN to UP, which is not a "recovery" (#31 caveat).</li>
 *   <li><b>Realtime (re)connect</b> ({@link #runNow}, from the resync step "siege startup work").</li>
 *   <li><b>Fallback retry</b> while work is pending and the API is not known to be DOWN: after a pass that left work, or
 *       at {@link #start()}, the next try comes after the next of {@code delays} (default 30 s, 1 min, 2 min, then every
 *       5 min). It covers a step that failed while the API stayed UP (an error answer, a timeout), a step still in
 *       flight when the UP transition came, and {@code api.connectivity.enabled: false} (the state stays UNKNOWN and no
 *       transition ever comes). While DOWN the fallback waits for the UP transition. An UP transition starts the delays
 *       from the first again.</li>
 * </ul>
 * Nothing runs once nothing is pending. The steps keep their own guards (link 10): the abort never runs once this run
 * created a match, the gate restore waits while a lockdown of this run holds gates. A step's owner runs the startup
 * attempt itself, so right after enable every step still reads pending; its retry body answers at once (or joins the
 * startup call still in flight) when that attempt got through, so the first trigger only marks such steps done. Only
 * what is still left after a pass is logged; the steps log their own failures and results.
 */
public final class StartupWorkRetry {

    /** Fallback delays: 30 s, 1 min, 2 min, then every 5 min (the last one repeats). */
    public static final List<Duration> DEFAULT_DELAYS = List.of(
        Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(5));

    private final PendingStartupWork work;
    private final Supplier<ApiConnectivityState> connectivity;
    private final BiConsumer<Duration, Runnable> scheduler;
    private final List<Duration> delays;
    private final String label;
    private final Logger logger;

    /** Bumped by every event that makes an armed fallback stale (a run, DOWN, stop). Guarded by {@code this}. */
    private long generation;
    /** The generation the armed fallback belongs to; it is armed only while this equals {@link #generation}. */
    private long armedFor = -1;
    /** Index into {@link #delays} for the next fallback. */
    private int attempt;
    private boolean stopped;

    /**
     * @param work         the pending steps (registered and run once at startup by their owners)
     * @param connectivity the API connectivity state now (UNKNOWN when the monitor is off)
     * @param scheduler    runs a task after a delay (the plugin: the Bukkit scheduler, main thread)
     * @param delays       fallback delays; the last one repeats
     * @param label        names the work in the log, e.g. "Siege startup work"
     */
    public StartupWorkRetry(PendingStartupWork work, Supplier<ApiConnectivityState> connectivity,
                            BiConsumer<Duration, Runnable> scheduler, List<Duration> delays, String label, Logger logger) {
        this.work = Objects.requireNonNull(work, "work");
        this.connectivity = Objects.requireNonNull(connectivity, "connectivity");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        if (delays == null || delays.isEmpty() || delays.stream().anyMatch(d -> d == null || d.isNegative() || d.isZero())) {
            throw new IllegalArgumentException("delays must be positive and not empty");
        }
        this.delays = List.copyOf(delays);
        this.label = label == null || label.isBlank() ? "Startup work" : label;
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** After the startup attempts were started: arms the fallback when something is pending (unless the API is DOWN). */
    public void start() {
        armFallback();
    }

    /** A connectivity transition (main thread): into UP runs the pending work now, DOWN pauses the fallback. */
    public void connectivityChanged(ApiConnectivityState now) {
        if (now == ApiConnectivityState.UP) {
            synchronized (this) {
                attempt = 0;
            }
            runNow("API connectivity UP");
        } else if (now == ApiConnectivityState.DOWN) {
            synchronized (this) {
                generation++; // the armed fallback is dropped; the UP transition retries
            }
        }
    }

    /**
     * Runs the pending steps now through the shared runner; completes with the names still pending (never fails). When
     * something is left, the fallback is armed again. Nothing runs when nothing is pending.
     */
    public CompletableFuture<List<String>> runNow(String trigger) {
        synchronized (this) {
            if (stopped || !work.hasPending()) {
                return CompletableFuture.completedFuture(work.pending());
            }
            generation++; // this run replaces the armed fallback; it arms the next one if needed
        }
        return work.runPending().thenApply(left -> {
            if (!left.isEmpty()) {
                logger.info(label + " still pending (" + trigger + "): " + left + "; retried when the API is back");
                armFallback();
            }
            return left;
        });
    }

    /** Stops the fallback (plugin disable); later triggers do nothing. */
    public synchronized void stop() {
        stopped = true;
        generation++;
    }

    /** Whether a fallback retry is armed now (tests, diagnostics). */
    public synchronized boolean fallbackArmed() {
        return !stopped && armedFor == generation;
    }

    private void armFallback() {
        long armed;
        Duration delay;
        synchronized (this) {
            if (stopped || !work.hasPending() || armedFor == generation) {
                return;
            }
            if (connectivity.get() == ApiConnectivityState.DOWN) {
                return; // the UP transition retries
            }
            armed = ++generation;
            armedFor = armed;
            delay = delays.get(Math.min(attempt, delays.size() - 1));
            attempt++;
        }
        scheduler.accept(delay, () -> {
            synchronized (this) {
                if (stopped || armed != generation) {
                    return;
                }
            }
            runNow("fallback retry after " + delay.toSeconds() + " s");
        });
    }
}

package net.knightsandkings.knk.core.realtime;

import net.knightsandkings.knk.core.connectivity.ApiConnectivityState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PandiO/knk-workspace#31: the siege startup work retried when the API is back, also without the realtime hub. */
class StartupWorkRetryTest {

    private static final Logger LOG = Logger.getLogger("test");

    /** A scheduler that only records; tests fire the tasks by hand. */
    private static final class ManualScheduler {
        final List<Duration> delays = new ArrayList<>();
        final List<Runnable> tasks = new ArrayList<>();

        void schedule(Duration delay, Runnable task) {
            delays.add(delay);
            tasks.add(task);
        }

        void fireLast() {
            tasks.get(tasks.size() - 1).run();
        }
    }

    /** The three siege steps in #31's order; each succeeds once {@code apiUp} is set. */
    private static final class SiegeSteps {
        final PendingStartupWork work = new PendingStartupWork();
        final List<String> order = new ArrayList<>();
        final boolean[] apiUp = {false};

        SiegeSteps() {
            for (String name : List.of("abort", "gates", "config")) {
                work.register(name, () -> {
                    order.add(name);
                    return CompletableFuture.completedFuture(apiUp[0]);
                });
            }
        }
    }

    private final ManualScheduler scheduler = new ManualScheduler();
    private ApiConnectivityState state = ApiConnectivityState.UNKNOWN;

    private StartupWorkRetry retry(PendingStartupWork work) {
        return new StartupWorkRetry(work, () -> state, scheduler::schedule, StartupWorkRetry.DEFAULT_DELAYS, "Siege", LOG);
    }

    @Test
    void connectivityUpAfterAnOutage_runsThePendingStepsInOrder_andASucceededStepNeverAgain() {
        SiegeSteps steps = new SiegeSteps();
        StartupWorkRetry retry = retry(steps.work);
        state = ApiConnectivityState.DOWN;
        retry.start();
        assertFalse(retry.fallbackArmed(), "while DOWN the UP transition retries, not a timer");
        retry.connectivityChanged(ApiConnectivityState.DOWN);
        assertEquals(List.of(), steps.order);

        steps.apiUp[0] = true;
        state = ApiConnectivityState.UP;
        retry.connectivityChanged(ApiConnectivityState.UP);
        assertEquals(List.of("abort", "gates", "config"), steps.order);
        assertEquals(List.of(), steps.work.pending());
        assertFalse(retry.fallbackArmed());

        // Later outages and recoveries do nothing: every step got through.
        steps.order.clear();
        retry.connectivityChanged(ApiConnectivityState.DOWN);
        retry.connectivityChanged(ApiConnectivityState.UP);
        assertEquals(List.of(), steps.order);
    }

    @Test
    void unknownToUp_countsToo() {
        // #31 caveat: a fetch failed during enable, the API is up at the monitor's first probe (UNKNOWN -> UP).
        SiegeSteps steps = new SiegeSteps();
        StartupWorkRetry retry = retry(steps.work);
        retry.start();
        steps.apiUp[0] = true;
        state = ApiConnectivityState.UP;
        retry.connectivityChanged(ApiConnectivityState.UP);
        assertEquals(List.of("abort", "gates", "config"), steps.order);
        assertEquals(List.of(), steps.work.pending());
    }

    @Test
    void aStepThatFailsWhileUp_isRetriedByTheFallback_withBackoff_untilItGetsThrough() {
        SiegeSteps steps = new SiegeSteps();
        StartupWorkRetry retry = retry(steps.work);
        state = ApiConnectivityState.UP;
        retry.start();
        assertTrue(retry.fallbackArmed());
        assertEquals(List.of(Duration.ofSeconds(30)), scheduler.delays);

        for (int i = 0; i < 5; i++) {
            scheduler.fireLast(); // the API answers with errors: still pending, the next delay is armed
        }
        assertEquals(List.of(Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(5),
            Duration.ofMinutes(5), Duration.ofMinutes(5)), scheduler.delays);
        assertEquals(15, steps.order.size());

        steps.apiUp[0] = true;
        scheduler.fireLast();
        assertEquals(List.of(), steps.work.pending());
        assertFalse(retry.fallbackArmed());
        assertEquals(6, scheduler.delays.size(), "nothing is armed once nothing is pending");
    }

    @Test
    void connectivityOff_staysUnknown_theFallbackStillRetries() {
        // api.connectivity.enabled: false - no transition ever comes.
        SiegeSteps steps = new SiegeSteps();
        StartupWorkRetry retry = retry(steps.work);
        retry.start();
        assertTrue(retry.fallbackArmed());
        steps.apiUp[0] = true;
        scheduler.fireLast();
        assertEquals(List.of("abort", "gates", "config"), steps.order);
        assertEquals(List.of(), steps.work.pending());
    }

    @Test
    void down_dropsTheArmedFallback_andUpStartsTheDelaysFromTheFirstAgain() {
        SiegeSteps steps = new SiegeSteps();
        StartupWorkRetry retry = retry(steps.work);
        state = ApiConnectivityState.UP;
        retry.start();
        scheduler.fireLast(); // 30 s: fails, 1 min armed

        state = ApiConnectivityState.DOWN;
        retry.connectivityChanged(ApiConnectivityState.DOWN);
        assertFalse(retry.fallbackArmed());
        steps.order.clear();
        scheduler.fireLast(); // the stale 1 min timer does nothing
        assertEquals(List.of(), steps.order);

        state = ApiConnectivityState.UP;
        retry.connectivityChanged(ApiConnectivityState.UP); // still failing
        assertEquals(List.of("abort", "gates", "config"), steps.order);
        assertEquals(Duration.ofSeconds(30), scheduler.delays.get(scheduler.delays.size() - 1));
    }

    @Test
    void hubReconnectAndConnectivityUpTogether_runEachStepOnce() {
        // Link 10's trigger (realtime reconnect) and this one share the runner: one pass, one queued after it.
        PendingStartupWork work = new PendingStartupWork();
        AtomicInteger abortCalls = new AtomicInteger();
        AtomicInteger gateCalls = new AtomicInteger();
        CompletableFuture<Boolean> abortAnswer = new CompletableFuture<>();
        work.register("abort", () -> {
            abortCalls.incrementAndGet();
            return abortAnswer;
        });
        work.register("gates", () -> {
            gateCalls.incrementAndGet();
            return CompletableFuture.completedFuture(true);
        });
        StartupWorkRetry retry = retry(work);
        state = ApiConnectivityState.UP;
        retry.start();

        retry.connectivityChanged(ApiConnectivityState.UP);
        CompletableFuture<List<String>> fromHub = retry.runNow("realtime reconnect");
        CompletableFuture<List<String>> fromHubAgain = retry.runNow("realtime reconnect");
        assertEquals(1, abortCalls.get());
        assertEquals(0, gateCalls.get(), "the gate step waits for the abort");

        abortAnswer.complete(true);
        assertEquals(List.of(), fromHub.join());
        assertEquals(List.of(), fromHubAgain.join());
        assertEquals(1, abortCalls.get());
        assertEquals(1, gateCalls.get());
    }

    @Test
    void stop_endsEveryTrigger() {
        SiegeSteps steps = new SiegeSteps();
        StartupWorkRetry retry = retry(steps.work);
        retry.start();
        retry.stop();
        assertFalse(retry.fallbackArmed());
        scheduler.fireLast();
        retry.connectivityChanged(ApiConnectivityState.UP);
        retry.runNow("realtime reconnect").join();
        assertEquals(List.of(), steps.order);
    }

    @Test
    void nothingPending_noRunAndNoTimer() {
        PendingStartupWork work = new PendingStartupWork();
        StartupWorkRetry retry = retry(work);
        retry.start();
        retry.connectivityChanged(ApiConnectivityState.UP);
        assertEquals(List.of(), scheduler.delays);
        assertThrows(IllegalArgumentException.class,
            () -> new StartupWorkRetry(work, () -> state, scheduler::schedule, List.of(), "x", LOG));
    }

    @Test
    void runPending_whileAPassRuns_queuesOnePassAfterIt_sharedByEveryCaller() {
        PendingStartupWork work = new PendingStartupWork();
        List<CompletableFuture<Boolean>> answers = new ArrayList<>();
        work.register("abort", () -> {
            CompletableFuture<Boolean> answer = new CompletableFuture<>();
            answers.add(answer);
            return answer;
        });
        CompletableFuture<List<String>> first = work.runPending();
        CompletableFuture<List<String>> second = work.runPending();
        CompletableFuture<List<String>> third = work.runPending();
        assertSame(second, third);
        assertEquals(1, answers.size(), "no second chain while the first runs");

        answers.get(0).complete(false); // the first pass fails; the queued one tries again
        assertEquals(List.of("abort"), first.join());
        assertEquals(2, answers.size());
        answers.get(1).complete(true);
        assertEquals(List.of(), second.join());
        assertFalse(work.hasPending());
    }
}

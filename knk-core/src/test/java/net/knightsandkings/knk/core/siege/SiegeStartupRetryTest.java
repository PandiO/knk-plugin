package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.connectivity.ApiConnectivityState;
import net.knightsandkings.knk.core.dataaccess.RetryPolicy;
import net.knightsandkings.knk.core.realtime.PendingStartupWork;
import net.knightsandkings.knk.core.realtime.StartupWorkRetry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PandiO/knk-workspace#31 with the realtime hub off: the real {@link SiegeMatchRecorder} as the first startup step,
 * retried by {@link StartupWorkRetry} on the connectivity UP transition.
 */
class SiegeStartupRetryTest {

    @TempDir
    Path dir;

    private static final RetryPolicy RETRY = RetryPolicy.builder()
            .maxAttempts(2).initialDelay(Duration.ofMillis(1)).maxDelay(Duration.ofMillis(2)).build();
    private static final Logger LOG = Logger.getLogger("test");

    private final SiegeMatchRecorderTest.FakeApi api = new SiegeMatchRecorderTest.FakeApi();
    private final List<String> later = new ArrayList<>();

    private SiegeMatchRecorder recorder() {
        return new SiegeMatchRecorder(api, RETRY, new SiegeResultSpool(dir.resolve("pending-results"), LOG), LOG);
    }

    private StartupWorkRetry retry(PendingStartupWork work) {
        return new StartupWorkRetry(work, () -> ApiConnectivityState.UP, (delay, task) -> { },
                StartupWorkRetry.DEFAULT_DELAYS, "[Siege] Startup work", LOG);
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }

    private long abortUnfinishedCalls() {
        return api.calls.stream().filter(c -> c.startsWith("abortUnfinished")).count();
    }

    @Test
    void apiDownAtStart_theAbortRunsOnceWhenTheApiIsUp_beforeTheLaterSteps() throws Exception {
        SiegeMatchRecorder recorder = recorder();
        for (int i = 0; i < 10; i++) api.abortUnfinishedFailures.add(SiegeMatchRecorderTest.network());
        await(recorder.recoverOnStartup());
        assertFalse(recorder.recovered());

        PendingStartupWork work = new PendingStartupWork();
        work.register("abort unfinished matches", recorder::retryRecovery);
        work.register("restore stale gates", () -> {
            later.add("gates after abort=" + recorder.recovered());
            return CompletableFuture.completedFuture(true);
        });
        StartupWorkRetry retry = retry(work);
        retry.start();

        api.abortUnfinishedFailures.clear();
        long before = abortUnfinishedCalls();
        assertEquals(List.of(), await(retry.runNow("API connectivity UP")));
        assertEquals(before + 1, abortUnfinishedCalls());
        assertEquals(List.of("gates after abort=true"), later);

        retry.connectivityChanged(ApiConnectivityState.UP); // a later recovery: nothing pending, no abort
        assertEquals(before + 1, abortUnfinishedCalls());
    }

    @Test
    void neverAbortsAMatchThisRunCreated_evenWhenTheApiComesBackLater() throws Exception {
        SiegeMatchRecorder recorder = recorder();
        for (int i = 0; i < 10; i++) api.abortUnfinishedFailures.add(SiegeMatchRecorderTest.network());
        await(recorder.recoverOnStartup());
        api.abortUnfinishedFailures.clear();

        PendingStartupWork work = new PendingStartupWork();
        work.register("abort unfinished matches", recorder::retryRecovery);
        StartupWorkRetry retry = retry(work);
        retry.start();

        await(recorder.createMatch(1, 100)); // a lobby ran on the cached config before the API was back
        api.calls.clear();

        assertEquals(List.of(), await(retry.runNow("API connectivity UP")));
        assertFalse(api.calls.contains("abortUnfinished SERVER_RESTART"));
        assertTrue(work.pending().isEmpty(), "the step is settled for this run; the next start aborts what is left");
    }
}

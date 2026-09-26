package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.dataaccess.RetryPolicy;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.Completion;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.Participant;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.ParticipantReward;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.RewardSummary;
import net.knightsandkings.knk.core.domain.siege.SiegeEndReason;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.SiegeMatchesCommandApi;
import net.knightsandkings.knk.core.siege.SiegeResultSpool.PendingAbort;
import net.knightsandkings.knk.core.siege.SiegeResultSpool.PendingComplete;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ConnectException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Siege Phase 6: retry, spool, replay and startup recovery around the match port. The fake API
 * answers each call from a per-operation queue of outcomes (value or failure), then succeeds.
 */
class SiegeMatchRecorderTest {

    @TempDir
    Path dir;

    private static final RetryPolicy RETRY = RetryPolicy.builder()
            .maxAttempts(2).initialDelay(Duration.ofMillis(1)).maxDelay(Duration.ofMillis(2)).build();

    /** Network failure: retryable. */
    static RuntimeException network() {
        return new ApiException("url", "IO error", new ConnectException("refused"));
    }

    static RuntimeException http(int status) {
        return new ApiException("url", status, "Request failed", "{\"code\":\"x\"}");
    }

    static class FakeApi implements SiegeMatchesCommandApi {
        final List<String> calls = new ArrayList<>();
        final Deque<RuntimeException> createFailures = new ArrayDeque<>();
        final Deque<RuntimeException> completeFailures = new ArrayDeque<>();
        final Deque<RuntimeException> abortFailures = new ArrayDeque<>();
        final Deque<RuntimeException> abortUnfinishedFailures = new ArrayDeque<>();
        List<Long> unfinished = List.of(40L, 41L);
        long nextId = 100;

        private static <T> CompletableFuture<T> outcome(Deque<RuntimeException> failures, T value) {
            RuntimeException failure = failures.poll();
            // Like the HTTP impl: the failure surfaces through supplyAsync, wrapped in a CompletionException.
            return failure == null ? CompletableFuture.completedFuture(value)
                    : CompletableFuture.failedFuture(new CompletionException(failure));
        }

        @Override
        public synchronized CompletableFuture<Long> createMatch(int siegeLobbyId, int siegeScenarioId) {
            calls.add("create " + siegeLobbyId + "/" + siegeScenarioId);
            return outcome(createFailures, nextId++);
        }

        @Override
        public synchronized CompletableFuture<Void> startMatch(long matchId, List<Participant> participants) {
            calls.add("start " + matchId);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public synchronized CompletableFuture<Void> participantLeft(long matchId, int userId, Instant leftAt) {
            calls.add("left " + matchId + "/" + userId);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public synchronized CompletableFuture<RewardSummary> completeMatch(long matchId, Completion completion) {
            calls.add("complete " + matchId);
            return outcome(completeFailures, new RewardSummary(matchId, List.of(
                    new ParticipantReward(7, true, true, 1, 1, 200, 20, 1))));
        }

        @Override
        public synchronized CompletableFuture<Void> abortMatch(long matchId, SiegeEndReason reason) {
            calls.add("abort " + matchId + " " + reason);
            return outcome(abortFailures, null);
        }

        @Override
        public synchronized CompletableFuture<List<Long>> abortUnfinished(SiegeEndReason reason) {
            calls.add("abortUnfinished " + reason);
            return outcome(abortUnfinishedFailures, unfinished);
        }
    }

    private final FakeApi api = new FakeApi();

    private SiegeResultSpool spool() {
        return new SiegeResultSpool(dir.resolve("pending-results"), Logger.getLogger("test"));
    }

    private SiegeMatchRecorder recorder(SiegeResultSpool spool) {
        return new SiegeMatchRecorder(api, RETRY, spool, Logger.getLogger("test"));
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }

    // ---- complete / abort ----

    @Test
    void complete_succeeds_returnsTheServersSummary_andSpoolsNothing() throws Exception {
        SiegeResultSpool spool = spool();
        RewardSummary summary = await(recorder(spool).completeMatch(9, SiegeResultSpoolTest.completion()));

        assertEquals(9, summary.matchId());
        assertEquals(200, summary.rewards().get(0).coins());
        assertTrue(spool.isEmpty());
    }

    @Test
    void complete_networkFailureOnce_isRetried() throws Exception {
        api.completeFailures.add(network());
        SiegeResultSpool spool = spool();

        RewardSummary summary = await(recorder(spool).completeMatch(9, SiegeResultSpoolTest.completion()));

        assertEquals(List.of("complete 9", "complete 9"), api.calls);
        assertEquals(9, summary.matchId());
        assertTrue(spool.isEmpty());
    }

    @Test
    void complete_stillFailingAfterRetries_orServerError_isSpooled_andCompletesWithNull() throws Exception {
        api.completeFailures.add(network());
        api.completeFailures.add(network());
        SiegeResultSpool spool = spool();
        SiegeMatchRecorder recorder = recorder(spool);

        assertNull(await(recorder.completeMatch(9, SiegeResultSpoolTest.completion())));
        assertEquals(List.of(new PendingComplete(9, SiegeResultSpoolTest.completion())), spool.list());

        api.abortFailures.add(http(503)); // 5xx isn't retried by RetryPolicy, but it is spooled
        assertNull(await(recorder.abortMatch(10, SiegeEndReason.ADMIN_STOPPED)));
        assertTrue(spool.contains(10));
    }

    @Test
    void complete_4xx_isFinal_neitherRetriedNorSpooled() throws Exception {
        api.completeFailures.add(http(409));
        SiegeResultSpool spool = spool();

        assertNull(await(recorder(spool).completeMatch(9, SiegeResultSpoolTest.completion())));

        assertEquals(List.of("complete 9"), api.calls);
        assertTrue(spool.isEmpty());
    }

    @Test
    void aSuccessfulCallRemovesAnOlderSpooledAttemptForTheSameMatch() throws Exception {
        SiegeResultSpool spool = spool();
        spool.save(new PendingAbort(9, SiegeEndReason.SERVER_RESTART));

        await(recorder(spool).completeMatch(9, SiegeResultSpoolTest.completion()));

        assertFalse(spool.contains(9));
    }

    @Test
    void spoolInFlight_writesCallsThatHaveNotAnsweredYet() {
        SiegeResultSpool spool = spool();
        CompletableFuture<RewardSummary> never = new CompletableFuture<>();
        SiegeMatchesCommandApi hanging = new FakeApi() {
            @Override
            public synchronized CompletableFuture<RewardSummary> completeMatch(long matchId, Completion completion) {
                return never;
            }
        };
        SiegeMatchRecorder recorder = new SiegeMatchRecorder(hanging, RETRY, spool, Logger.getLogger("test"));

        recorder.completeMatch(9, SiegeResultSpoolTest.completion());
        recorder.spoolInFlight();

        assertEquals(List.of(new PendingComplete(9, SiegeResultSpoolTest.completion())), spool.list());
    }

    // ---- startup recovery ----

    @Test
    void recovery_replaysTheSpoolFirst_thenAbortsUnfinishedMatches() throws Exception {
        SiegeResultSpool spool = spool();
        spool.save(new PendingComplete(12, SiegeResultSpoolTest.completion()));
        spool.save(new PendingAbort(11, SiegeEndReason.ADMIN_STOPPED));

        SiegeMatchRecorder.RecoveryReport report = await(recorder(spool).recoverOnStartup());

        assertEquals(List.of("abort 11 ADMIN_STOPPED", "complete 12", "abortUnfinished SERVER_RESTART"), api.calls);
        assertEquals(2, report.replayed());
        assertEquals(List.of(40L, 41L), report.abortedMatchIds());
        assertFalse(report.abortSkipped());
        assertTrue(spool.isEmpty());
    }

    @Test
    void recovery_dropsResultsTheServerRefuses_andKeepsOnesItCantReach_skippingTheAbort() throws Exception {
        SiegeResultSpool spool = spool();
        spool.save(new PendingAbort(11, SiegeEndReason.ADMIN_STOPPED));
        spool.save(new PendingComplete(12, SiegeResultSpoolTest.completion()));
        api.abortFailures.add(http(409));      // match 11 already completed: drop
        api.completeFailures.add(network());   // match 12: keep

        SiegeMatchRecorder.RecoveryReport report = await(recorder(spool).recoverOnStartup());

        assertEquals(1, report.dropped());
        assertEquals(1, report.stillPending());
        assertTrue(report.abortSkipped());
        assertFalse(api.calls.contains("abortUnfinished SERVER_RESTART")); // never abort a spooled completion
        assertEquals(List.of(new PendingComplete(12, SiegeResultSpoolTest.completion())), spool.list());
    }

    @Test
    void createMatch_waitsForRecovery_andRunsUnrecordedWhenItFails() throws Exception {
        SiegeResultSpool spool = spool();
        SiegeMatchRecorder recorder = recorder(spool);
        recorder.recoverOnStartup();

        assertEquals(100L, await(recorder.createMatch(1, 100)));
        assertEquals("abortUnfinished SERVER_RESTART", api.calls.get(0)); // recovery ran before the create

        api.createFailures.add(network());
        api.createFailures.add(network());
        assertNull(await(recorder.createMatch(1, 100)));
    }

    @Test
    void createMatch_replaysLeftoverSpooledResults() throws Exception {
        SiegeResultSpool spool = spool();
        spool.save(new PendingAbort(11, SiegeEndReason.ADMIN_STOPPED));
        SiegeMatchRecorder recorder = recorder(spool);

        await(recorder.createMatch(1, 100));
        await(recorder.replaySpool()); // lets the opportunistic replay finish (or no-ops if it already did)

        assertTrue(api.calls.contains("abort 11 ADMIN_STOPPED"));
        assertTrue(spool.isEmpty());
    }

    @Test
    void otherCallsFailQuietly() throws Exception {
        SiegeMatchesCommandApi failing = new FakeApi() {
            @Override
            public synchronized CompletableFuture<Void> startMatch(long matchId, List<Participant> participants) {
                return CompletableFuture.failedFuture(new CompletionException(http(409)));
            }
        };
        SiegeMatchRecorder recorder = new SiegeMatchRecorder(failing, RETRY, spool(), Logger.getLogger("test"));

        assertNull(await(recorder.startMatch(1, List.of(new Participant(7, 202)))));
    }

    @Test
    void finalRejection_isA4xxAnywhereInTheCauseChain() {
        assertTrue(SiegeMatchRecorder.isFinalRejection(new CompletionException(http(400))));
        assertTrue(SiegeMatchRecorder.isFinalRejection(http(404)));
        assertFalse(SiegeMatchRecorder.isFinalRejection(http(500)));
        assertFalse(SiegeMatchRecorder.isFinalRejection(new CompletionException(network())));
        assertFalse(SiegeMatchRecorder.isFinalRejection(new IllegalStateException("x")));
    }
}

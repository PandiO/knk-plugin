package net.knightsandkings.knk.core.statistics;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.knightsandkings.knk.core.dataaccess.RetryPolicy;
import net.knightsandkings.knk.core.domain.statistics.StatisticsBatch;
import net.knightsandkings.knk.core.domain.statistics.StatisticsBatchResult;
import net.knightsandkings.knk.core.domain.statistics.StatisticsCatalog;
import net.knightsandkings.knk.core.domain.statistics.StatisticsVisibilitySettings;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.StatisticsApi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** IMPLEMENTATION_PLAN.md §5.2: deliver, spool on transient failures, drop on 400, replay oldest first. */
class StatisticsRecorderTest {

    private static final Instant T0 = Instant.parse("2026-10-03T10:00:00Z");

    @TempDir
    Path dir;

    /** Answers each call with the next scripted answer (a result or a failure). */
    private static class ScriptedApi implements StatisticsApi {
        final List<Object> answers = new ArrayList<>();
        final List<UUID> sent = new ArrayList<>();

        @Override
        public CompletableFuture<StatisticsBatchResult> postBatch(StatisticsBatch batch) {
            sent.add(batch.batchId());
            Object answer = answers.isEmpty() ? new RuntimeException("no answer scripted") : answers.remove(0);
            if (answer instanceof Throwable error) {
                return CompletableFuture.failedFuture(new RuntimeException("Failed", error));
            }
            StatisticsBatchResult result = (StatisticsBatchResult) answer;
            return CompletableFuture.completedFuture(new StatisticsBatchResult(batch.batchId(), result.duplicate(),
                    result.accepted(), result.rejected()));
        }

        @Override
        public CompletableFuture<StatisticsCatalog> getCatalog() {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<net.knightsandkings.knk.core.domain.statistics.PlayerStatistics> getUserStatistics(
                int userId, Integer actingUserId, String period, java.time.LocalDate date) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<net.knightsandkings.knk.core.domain.common.Page<net.knightsandkings.knk.core.domain.statistics.TitleChange>>
                getTitleHistory(int userId, Integer actingUserId, int page, int pageSize) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<StatisticsVisibilitySettings> getVisibility(int userId, int actingUserId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<StatisticsVisibilitySettings> updateVisibility(int userId, int actingUserId,
                                                                                List<StatisticsVisibilitySettings.Change> changes) {
            throw new UnsupportedOperationException();
        }
    }

    private final ScriptedApi api = new ScriptedApi();

    private StatisticsRecorder recorder() {
        return new StatisticsRecorder(api, RetryPolicy.noRetry(), new StatisticsSpool(dir, Logger.getAnonymousLogger()),
                Logger.getAnonymousLogger());
    }

    private static StatisticsBatchResult ok(int accepted) {
        return new StatisticsBatchResult(null, false, accepted, List.of());
    }

    private static ApiException http(int status) {
        return new ApiException("http://api.test/api/statistics/batches", status, "Request failed", "{}");
    }

    private static StatisticsBatch batch(Instant sentAt) {
        return StatisticsSpoolTest.sample(UUID.randomUUID(), sentAt);
    }

    @Test
    void aDeliveredBatchIsNotSpooled() {
        api.answers.add(new StatisticsBatchResult(null, false, 5, List.of(new StatisticsBatchResult.Rejection("pvpKills", 0, "UnknownUser"))));
        StatisticsRecorder recorder = recorder();

        StatisticsRecorder.Outcome outcome = recorder.send(batch(T0)).join();

        assertEquals(StatisticsRecorder.Status.DELIVERED, outcome.status());
        assertEquals(1, outcome.result().rejected().size());
        assertTrue(recorder.spool().isEmpty());
    }

    @Test
    void aDuplicateAnswerIsReported() {
        api.answers.add(new StatisticsBatchResult(null, true, 0, List.of()));
        assertEquals(StatisticsRecorder.Status.DUPLICATE, recorder().send(batch(T0)).join().status());
    }

    @Test
    void transientFailuresAreSpooled() {
        for (Object failure : List.of(http(503), http(500), http(401), http(403), http(408), http(429),
                new java.io.IOException("connection refused"))) {
            api.answers.add(failure);
            StatisticsRecorder recorder = recorder();
            StatisticsBatch batch = batch(T0);

            assertEquals(StatisticsRecorder.Status.SPOOLED, recorder.send(batch).join().status(), String.valueOf(failure));
            assertTrue(recorder.spool().contains(batch.batchId()));
            recorder.spool().delete(batch.batchId());
        }
    }

    @Test
    void aBadRequestIsFinal() {
        api.answers.add(http(400));
        StatisticsRecorder recorder = recorder();

        assertEquals(StatisticsRecorder.Status.DROPPED, recorder.send(batch(T0)).join().status());
        assertTrue(recorder.spool().isEmpty());
    }

    @Test
    void replayDeliversOldestFirstAndDeletesDeliveredAndDuplicateFiles() {
        StatisticsRecorder recorder = recorder();
        StatisticsBatch older = batch(T0);
        StatisticsBatch newer = batch(T0.plusSeconds(60));
        recorder.spool().write(newer);
        recorder.spool().write(older);
        api.answers.add(ok(5));
        api.answers.add(new StatisticsBatchResult(null, true, 0, List.of()));

        assertEquals(2, recorder.replay(10).join());

        assertEquals(List.of(older.batchId(), newer.batchId()), api.sent);
        assertTrue(recorder.spool().isEmpty());
    }

    @Test
    void replayStopsAtTheFirstTransientFailureAndKeepsTheRest() {
        StatisticsRecorder recorder = recorder();
        StatisticsBatch older = batch(T0);
        StatisticsBatch newer = batch(T0.plusSeconds(60));
        recorder.spool().write(older);
        recorder.spool().write(newer);
        api.answers.add(http(503));

        assertEquals(0, recorder.replay(10).join());

        assertEquals(List.of(older.batchId()), api.sent);
        assertEquals(2, recorder.spool().count());
    }

    @Test
    void replayDropsARefusedFileAndGoesOn() {
        StatisticsRecorder recorder = recorder();
        recorder.spool().write(batch(T0));
        recorder.spool().write(batch(T0.plusSeconds(1)));
        api.answers.add(http(400));
        api.answers.add(ok(1));

        assertEquals(2, recorder.replay(10).join());
        assertTrue(recorder.spool().isEmpty());
    }

    @Test
    void replayHonoursTheRunLimit() {
        StatisticsRecorder recorder = recorder();
        for (int i = 0; i < 3; i++) {
            recorder.spool().write(batch(T0.plusSeconds(i)));
        }
        api.answers.add(ok(1));
        api.answers.add(ok(1));

        assertEquals(2, recorder.replay(2).join());
        assertEquals(1, recorder.spool().count());
    }

    @Test
    void batchesInFlightAtShutdownAreSpooled() {
        StatisticsApi hanging = new ScriptedApi() {
            @Override
            public CompletableFuture<StatisticsBatchResult> postBatch(StatisticsBatch batch) {
                return new CompletableFuture<>();
            }
        };
        StatisticsRecorder recorder = new StatisticsRecorder(hanging, RetryPolicy.noRetry(),
                new StatisticsSpool(dir, Logger.getAnonymousLogger()), Logger.getAnonymousLogger());
        StatisticsBatch batch = batch(T0);
        CompletableFuture<StatisticsRecorder.Outcome> pending = recorder.send(batch);

        recorder.spoolInFlight();

        assertFalse(pending.isDone());
        assertTrue(recorder.spool().contains(batch.batchId()));
    }

    @Test
    void finalRejectionClassification() {
        assertTrue(StatisticsRecorder.isFinalRejection(http(400)));
        assertTrue(StatisticsRecorder.isFinalRejection(new RuntimeException(http(404))));
        assertFalse(StatisticsRecorder.isFinalRejection(http(503)));
        assertFalse(StatisticsRecorder.isFinalRejection(http(401)));
        assertFalse(StatisticsRecorder.isFinalRejection(new java.io.IOException("x")));
    }
}

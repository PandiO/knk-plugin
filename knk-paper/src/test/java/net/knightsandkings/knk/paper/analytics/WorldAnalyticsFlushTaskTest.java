package net.knightsandkings.knk.paper.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.analytics.WorldAnalyticsWindow;
import net.knightsandkings.knk.core.domain.analytics.WorldAnalyticsBatch;
import net.knightsandkings.knk.core.domain.statistics.StatisticsCatalog;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.WorldAnalyticsApi;

/**
 * KNG-34 link 7 (IMPLEMENTATION_PLAN.md §3.4): batches go out with the server name; answered batches are
 * done, refused ones (400/503…) dropped, transient failures kept in memory and retried with the same id;
 * the catalogue's time zone is applied.
 */
class WorldAnalyticsFlushTaskTest {

    private final WorldAnalyticsWindow window =
        new WorldAnalyticsWindow(16, Clock.fixed(Instant.parse("2026-10-03T10:00:00Z"), ZoneOffset.UTC), ZoneOffset.UTC, 12);

    static final class FakeApi implements WorldAnalyticsApi {
        final List<WorldAnalyticsBatch> sent = new ArrayList<>();
        final List<String> servers = new ArrayList<>();
        Function<WorldAnalyticsBatch, CompletableFuture<BatchResult>> answer =
            batch -> CompletableFuture.completedFuture(new BatchResult(false, batch.rowCount(), 0));

        @Override
        public CompletableFuture<BatchResult> postBatch(WorldAnalyticsBatch batch, String serverName) {
            sent.add(batch);
            servers.add(serverName);
            return answer.apply(batch);
        }
    }

    private static CompletableFuture<WorldAnalyticsApi.BatchResult> failing(Throwable cause) {
        return CompletableFuture.failedFuture(new CompletionException(cause));
    }

    @Test
    void anAnsweredBatch_isDone() {
        FakeApi api = new FakeApi();
        window.sample("world", 0, 0);

        new WorldAnalyticsFlushTask(window, api, "paper 25565").flush().join();

        assertEquals(1, api.sent.size());
        assertEquals(List.of("paper 25565"), api.servers);
        assertEquals(0, window.pendingBatches());
    }

    @Test
    void nothingRecorded_sendsNothing() {
        FakeApi api = new FakeApi();

        new WorldAnalyticsFlushTask(window, api, "s").flush().join();

        assertTrue(api.sent.isEmpty());
    }

    @Test
    void transientFailures_areRetriedWithTheSameId() {
        FakeApi api = new FakeApi();
        api.answer = batch -> failing(new IOException("connection refused"));
        WorldAnalyticsFlushTask task = new WorldAnalyticsFlushTask(window, api, "s");
        window.sample("world", 0, 0);

        task.flush().join();
        assertEquals(1, window.pendingBatches());

        api.answer = batch -> failing(new ApiException("http://api/world-analytics/batches", 500, "boom", ""));
        task.flush().join();
        assertEquals(1, window.pendingBatches());

        api.answer = batch -> CompletableFuture.completedFuture(new WorldAnalyticsApi.BatchResult(false, 1, 0));
        task.flush().join();
        assertEquals(0, window.pendingBatches());
        assertEquals(3, api.sent.size());
        assertEquals(api.sent.get(0).batchId(), api.sent.get(2).batchId());
    }

    @Test
    void refusedOrDisabled_isDropped() {
        for (int status : new int[] {400, 503}) {
            FakeApi api = new FakeApi();
            api.answer = batch -> failing(new ApiException("u", status, "no", ""));
            window.sample("world", 0, 0);

            new WorldAnalyticsFlushTask(window, api, "s").flush().join();

            assertEquals(0, window.pendingBatches(), "status " + status);
        }
        assertTrue(WorldAnalyticsFlushTask.isFinal(new RuntimeException(new ApiException("u", 422, "x", ""))));
        assertFalse(WorldAnalyticsFlushTask.isFinal(new RuntimeException(new ApiException("u", 401, "x", ""))));
        assertFalse(WorldAnalyticsFlushTask.isFinal(new IOException("down")));
    }

    @Test
    void anApiThatThrowsSynchronously_isATransientFailure() {
        FakeApi api = new FakeApi();
        api.answer = batch -> {
            throw new IllegalStateException("client closed");
        };
        window.sample("world", 0, 0);

        new WorldAnalyticsFlushTask(window, api, "s").flush().join();

        assertEquals(1, window.pendingBatches());
    }

    @Test
    void theCatalogueTimeZone_isApplied_andAFailureKeepsTheDefault() {
        WorldAnalyticsFlushTask task = new WorldAnalyticsFlushTask(window, new FakeApi(), "s");

        task.readTimeZone(() -> CompletableFuture.failedFuture(new IOException("down"))).join();
        assertEquals(ZoneOffset.UTC, window.zone());
        task.readTimeZone(() -> CompletableFuture.completedFuture(new StatisticsCatalog("Not/AZone", List.of(), List.of(), List.of()))).join();
        assertEquals(ZoneOffset.UTC, window.zone());
        task.readTimeZone(() -> CompletableFuture.completedFuture(new StatisticsCatalog("Europe/Amsterdam", List.of(), List.of(), List.of()))).join();
        assertEquals(ZoneId.of("Europe/Amsterdam"), window.zone());
    }
}

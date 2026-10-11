package net.knightsandkings.knk.paper.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.ports.api.TelemetryApi;
import net.knightsandkings.knk.core.telemetry.TelemetryBuffer;
import net.knightsandkings.knk.core.telemetry.TelemetryClientConfig;
import net.knightsandkings.knk.core.telemetry.TelemetryEvent;
import net.knightsandkings.knk.core.telemetry.TelemetryEventNames;

/**
 * Sending (KNG-34 link 6, acceptance criterion 2 / L1-23): batches of ≤ 500, a failed batch is
 * dropped and reported once as telemetry.dropped, never retried; one flush at a time; config polls
 * update the emitter or keep the last config.
 */
class TelemetryFlushTaskTest {

    final TelemetryBuffer buffer = new TelemetryBuffer(5000);
    final TelemetryEmitter emitter = TelemetryEmitterTest.emitter(buffer);
    final FakeApi api = new FakeApi();
    final TelemetryFlushTask task = new TelemetryFlushTask(emitter, api);

    static final class FakeApi implements TelemetryApi {
        final List<List<TelemetryEvent>> batches = new ArrayList<>();
        boolean fail;
        CompletableFuture<BatchResult> pending;
        CompletableFuture<TelemetryClientConfig> config = CompletableFuture.failedFuture(new RuntimeException("down"));

        @Override
        public CompletableFuture<BatchResult> postBatch(List<TelemetryEvent> events) {
            batches.add(events);
            if (pending != null) {
                return pending;
            }
            return fail ? CompletableFuture.failedFuture(new RuntimeException("503")) :
                CompletableFuture.completedFuture(new BatchResult(events.size(), 0, 0, 0));
        }

        @Override
        public CompletableFuture<TelemetryClientConfig> getConfig() {
            return config;
        }
    }

    void emit(int count) {
        for (int i = 0; i < count; i++) {
            emitter.event(TelemetryEventNames.MENU_OPENED).player(TelemetryEmitterTest.ALICE).emit();
        }
    }

    @Test
    void sendsInBatchesOf500() {
        emit(1200);

        task.flush().join();

        assertEquals(List.of(500, 500, 200), api.batches.stream().map(List::size).toList());
        assertEquals(0, buffer.size());
    }

    @Test
    void aFailedBatch_isDroppedAndReportedOnce_nextFlush() {
        emit(3);
        api.fail = true;
        task.flush().join();
        assertEquals(0, buffer.size(), "nothing is kept for a retry");

        api.fail = false;
        task.flush().join();

        List<TelemetryEvent> report = api.batches.get(1);
        assertEquals(1, report.size());
        assertEquals(TelemetryEventNames.TELEMETRY_DROPPED, report.get(0).name());
        assertEquals(3L, report.get(0).payload().get("dropped"));
    }

    @Test
    void oneFlushAtATime() {
        emit(2);
        api.pending = new CompletableFuture<>();
        CompletableFuture<Void> first = task.flush();
        emit(2);

        task.flush().join();
        assertEquals(1, api.batches.size(), "the second flush is skipped while the first is in flight");
        assertFalse(first.isDone());

        api.pending.complete(new TelemetryApi.BatchResult(2, 0, 0, 0));
        api.pending = null;
        assertTrue(first.isDone());
        task.flush().join();
        assertEquals(2, api.batches.size());
    }

    @Test
    void configPolls_updateOrKeepTheLastConfig() {
        TelemetryClientConfig enhanced = new TelemetryClientConfig(true, Set.of(7), List.of(3), Set.of(), Set.of(), Set.of());
        api.config = CompletableFuture.completedFuture(enhanced);
        task.pollConfig().join();
        assertEquals(enhanced, emitter.config());

        api.config = CompletableFuture.failedFuture(new RuntimeException("down"));
        task.pollConfig().join();
        assertEquals(enhanced, emitter.config());
    }

    @Test
    void emptyBuffer_sendsNothing() {
        task.flush().join();
        assertTrue(api.batches.isEmpty());
    }
}

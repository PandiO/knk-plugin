package net.knightsandkings.knk.core.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/** The bounded diagnostic buffer (KNG-34 link 6, §7: ≤ max-buffer-events, oldest dropped and counted). */
class TelemetryBufferTest {

    static TelemetryEvent event(String name, long seq) {
        return new TelemetryEvent(UUID.randomUUID(), name, 1, Instant.parse("2026-10-03T10:00:00Z"), "paper", seq, "1.0",
            TelemetryEvent.Level.BASELINE, 7, null, null, null, null, "session", "join", TelemetryEvent.Outcome.INFO,
            null, null, null, Map.of());
    }

    @Test
    void drainsOldestFirst_upToTheLimit() {
        TelemetryBuffer buffer = new TelemetryBuffer(10);
        for (int i = 1; i <= 5; i++) buffer.add(event("session.join", i));

        assertEquals(List.of(1L, 2L, 3L), buffer.drain(3).stream().map(TelemetryEvent::serverSeq).toList());
        assertEquals(List.of(4L, 5L), buffer.drain(10).stream().map(TelemetryEvent::serverSeq).toList());
        assertEquals(0, buffer.size());
        assertEquals(List.of(), buffer.drain(10));
    }

    @Test
    void whenFull_dropsTheOldest_andCountsIt() {
        TelemetryBuffer buffer = new TelemetryBuffer(3);
        for (int i = 1; i <= 5; i++) buffer.add(event("session.join", i));

        assertEquals(3, buffer.size());
        assertEquals(2, buffer.droppedSinceStart());
        assertEquals(2, buffer.takeUnreportedDrops());
        assertEquals(0, buffer.takeUnreportedDrops());
        assertEquals(List.of(3L, 4L, 5L), buffer.drain(10).stream().map(TelemetryEvent::serverSeq).toList());
    }

    @Test
    void failedSends_countAsDrops() {
        TelemetryBuffer buffer = new TelemetryBuffer(3);
        buffer.recordDropped(40);
        buffer.recordDropped(-1);

        assertEquals(40, buffer.takeUnreportedDrops());
        assertEquals(40, buffer.droppedSinceStart());
        assertThrows(IllegalArgumentException.class, () -> new TelemetryBuffer(0));
    }

    @Test
    void manyThreads_neverExceedTheCapacity() throws Exception {
        TelemetryBuffer buffer = new TelemetryBuffer(1000);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch done = new CountDownLatch(4);
        for (int t = 0; t < 4; t++) {
            pool.execute(() -> {
                for (int i = 0; i < 1000; i++) buffer.add(event("menu.opened", i));
                done.countDown();
            });
        }
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        List<TelemetryEvent> all = new ArrayList<>(buffer.drain(5000));
        assertEquals(1000, all.size());
        assertEquals(3000, buffer.droppedSinceStart());
    }
}

package net.knightsandkings.knk.core.roads.walk;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The phase 0 measurement recorder (PandiO/knk-workspace#703): recent samples, max/p95/p50 over the window, the
 * cap counts, reset, and the lines {@code /knk road status} shows.
 */
class WalkMeasurementsTest {

    private static WalkSearchStats search(int expansions, long micros, WalkSearchStats.Cap cap, boolean pruned) {
        return new WalkSearchStats(expansions, expansions * 2, micros * 1_000L, cap, pruned);
    }

    private static WalkMeasurements.Capture capture(int chunks, int hits, long micros) {
        return new WalkMeasurements.Capture(chunks, hits, micros * 1_000L, 100L);
    }

    @Test
    void anEmptyRecorderSaysSo() {
        WalkMeasurements m = new WalkMeasurements();

        assertEquals(List.of("no searches since the last reset"), m.describe(5));
        assertEquals(0, m.summary().total());
        assertEquals(0L, m.summary().searchMicros().max());
    }

    @Test
    void recentSamplesAreNewestFirst() {
        WalkMeasurements m = new WalkMeasurements();
        for (int i = 1; i <= 4; i++) {
            m.record(WalkResult.Status.FOUND, search(i, i, WalkSearchStats.Cap.NONE, false), capture(i, 0, i), 0, i, i);
        }

        List<WalkMeasurements.Sample> recent = m.recent(3);
        assertEquals(List.of(4L, 3L, 2L), recent.stream().map(WalkMeasurements.Sample::seq).toList());
        assertEquals(4, m.recent(10).size(), "never more than recorded");
    }

    @Test
    void theSpreadIsMaxP95AndP50ByNearestRank() {
        WalkMeasurements m = new WalkMeasurements();
        for (int i = 1; i <= 100; i++) {
            m.record(WalkResult.Status.FOUND, search(i * 10, i, WalkSearchStats.Cap.NONE, false), capture(i, 0, i * 2L),
                i * 1_000L, 50, 60);
        }

        WalkMeasurements.Summary s = m.summary();
        assertEquals(new WalkMeasurements.Spread(100, 95, 50), s.searchMicros());
        assertEquals(new WalkMeasurements.Spread(1000, 950, 500), s.expansions());
        assertEquals(new WalkMeasurements.Spread(2000, 1900, 1000), s.cells());
        assertEquals(new WalkMeasurements.Spread(200, 190, 100), s.captureMicros());
        assertEquals(new WalkMeasurements.Spread(100, 95, 50), s.chunks());
        assertEquals(new WalkMeasurements.Spread(100, 95, 50), s.queueMicros());
        assertEquals(100, s.total());
        assertEquals(100, s.window());
    }

    @Test
    void outcomesAndCapsAreCounted() {
        WalkMeasurements m = new WalkMeasurements();
        m.record(WalkResult.Status.FOUND, search(10, 1, WalkSearchStats.Cap.NONE, true), capture(4, 1, 5), 0, 30, 40);
        m.record(WalkResult.Status.FALLBACK, search(20_000, 90_000, WalkSearchStats.Cap.MAX_EXPANSIONS, false),
            capture(64, 10, 900), 0, 140, 0);
        m.record(WalkResult.Status.FALLBACK, search(300, 900, WalkSearchStats.Cap.MAX_LENGTH, true), capture(9, 0, 50),
            0, 80, 20);
        m.record(WalkResult.Status.NO_PATH, search(50, 3, WalkSearchStats.Cap.NONE, false), null, 0, 10, 0);

        WalkMeasurements.Summary s = m.summary();
        assertEquals(1, s.found());
        assertEquals(1, s.noPath());
        assertEquals(2, s.fallback());
        assertEquals(1, s.maxExpansionsCap());
        assertEquals(1, s.maxLengthCap());
        assertEquals(2, s.lengthPruned());
        assertEquals(64, s.chunks().max(), "the sample without a capture is left out of the capture spreads");
        assertEquals(9, s.chunks().p50(), "nearest rank of 4, 9, 64");
    }

    @Test
    void theWindowKeepsTheLatestSamples() {
        WalkMeasurements m = new WalkMeasurements();
        for (int i = 0; i < WalkMeasurements.WINDOW + 10; i++) {
            m.record(WalkResult.Status.FOUND, search(i, i, WalkSearchStats.Cap.NONE, false), null, 0, 1, 1);
        }

        WalkMeasurements.Summary s = m.summary();
        assertEquals(WalkMeasurements.WINDOW + 10, s.total());
        assertEquals(WalkMeasurements.WINDOW, s.window());
        assertEquals(WalkMeasurements.WINDOW + 9, s.expansions().max());
        assertEquals(WalkMeasurements.WINDOW + 10L, m.recent(1).get(0).seq());
    }

    @Test
    void resetStartsAFreshSceneButKeepsTheRunningNumber() {
        WalkMeasurements m = new WalkMeasurements();
        m.record(WalkResult.Status.FOUND, search(1, 1, WalkSearchStats.Cap.NONE, false), null, 0, 1, 1);
        m.record(WalkResult.Status.FOUND, search(1, 1, WalkSearchStats.Cap.NONE, false), null, 0, 1, 1);

        m.reset();

        assertEquals(0, m.summary().total());
        assertTrue(m.recent(5).isEmpty());
        WalkMeasurements.Sample next = m.record(WalkResult.Status.FOUND, search(1, 1, WalkSearchStats.Cap.NONE, false),
            null, 0, 1, 1);
        assertEquals(3L, next.seq(), "running numbers stay unique across resets");
        assertEquals(1, m.summary().total());
    }

    @Test
    void theStatusLinesNameEveryCounter() {
        WalkMeasurements m = new WalkMeasurements();
        m.record(WalkResult.Status.FOUND, search(1200, 4100, WalkSearchStats.Cap.NONE, true), capture(36, 12, 3200), 15_000,
            95.2, 101.4);
        m.record(WalkResult.Status.FALLBACK, search(20_000, 120_000, WalkSearchStats.Cap.MAX_EXPANSIONS, false), null, 0,
            140, 0);

        List<String> lines = m.describe(5);

        assertEquals("2 searches since the last reset; last 2: found 1, no path 0, budget 1 (max-expansions 1, "
            + "max-length 0); length-pruned 1", lines.get(0));
        assertTrue(lines.get(1).startsWith("search µs: max 120000, p95 120000, p50 4100; queue µs: max 15"), lines.get(1));
        assertTrue(lines.get(2).startsWith("expansions: max 20000"), lines.get(2));
        assertTrue(lines.get(3).startsWith("capture µs: max 3200"), lines.get(3));
        assertTrue(lines.get(3).contains("chunks: max 36"), lines.get(3));
        assertEquals("#2 FALLBACK (max-expansions), 140 blocks straight, 20000 expansions, 40000 cells, search 120000 µs",
            lines.get(4));
        assertEquals("#1 FOUND, 95 blocks straight, path 101, 1200 expansions, 2400 cells, 36 chunks (12 cached), "
            + "capture 3200 µs, search 4100 µs, length-pruned", lines.get(5));
        assertEquals(6, lines.size());
    }

    @Test
    void walkThreadsMayRecordWhileTheMainThreadReads() throws Exception {
        WalkMeasurements m = new WalkMeasurements();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch go = new CountDownLatch(1);
        for (int t = 0; t < 4; t++) {
            pool.execute(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                for (int i = 0; i < 1_000; i++) {
                    m.record(WalkResult.Status.FOUND, search(i, i, WalkSearchStats.Cap.NONE, false), null, 0, 1, 1);
                }
            });
        }
        go.countDown();
        for (int i = 0; i < 50; i++) {
            assertFalse(m.describe(5).isEmpty());
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(4_000, m.summary().total());
        assertEquals(WalkMeasurements.WINDOW, m.summary().window());
    }
}

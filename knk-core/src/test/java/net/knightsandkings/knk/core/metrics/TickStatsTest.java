package net.knightsandkings.knk.core.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TickStatsTest {

    @Test
    void computesAverageNearestRankP95AndMax() {
        long[] ticks = new long[100];
        for (int i = 0; i < 100; i++) {
            ticks[i] = (i + 1) * 1_000_000L; // 1..100 ms
        }

        TickStats stats = TickStats.fromNanos(ticks);

        assertEquals(100, stats.samples());
        assertEquals(0.0505, stats.avgSeconds(), 1e-9);
        assertEquals(0.095, stats.p95Seconds(), 1e-9);
        assertEquals(0.100, stats.maxSeconds(), 1e-9);
    }

    @Test
    void unusedSlotsAndMissingDataAreIgnored() {
        TickStats stats = TickStats.fromNanos(new long[]{0, 0, 20_000_000L, 40_000_000L});

        assertEquals(2, stats.samples());
        assertEquals(0.03, stats.avgSeconds(), 1e-9);
        assertEquals(TickStats.EMPTY, TickStats.fromNanos(null));
        assertEquals(TickStats.EMPTY, TickStats.fromNanos(new long[]{0, 0}));
    }
}

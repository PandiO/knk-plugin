package net.knightsandkings.knk.core.roads.walk;

import net.knightsandkings.knk.core.roads.build.GridFixture;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The walk search's phase 0 counters (PandiO/knk-workspace#703, DESIGN § 9.2): expansions, cells, time and the cap
 * that ended the search, and that the counters change no result.
 */
class WalkSearchStatsTest {

    private static final String G = GridFixture.GRASS;

    /** A clock that moves 1 000 ns at every read: the search reads it twice, so the time is exactly 1 000 ns. */
    private static WalkSearch stepping() {
        AtomicLong now = new AtomicLong();
        return new WalkSearch(() -> now.addAndGet(1_000L));
    }

    @Test
    void aFoundPathCountsItsExpansionsCellsAndTime() {
        WalkFixture f = new WalkFixture().floor(0, 0, 20, 4, 64, G);
        WalkResult result = stepping().find(f.request(0, 64, 2, 20, 64, 2));

        assertTrue(result.isFound(), result.toString());
        WalkSearchStats stats = result.stats();
        assertEquals(result.expansions(), stats.expansions(), "the same number as the result's");
        assertTrue(stats.cells() > stats.expansions(), "the open list holds cells not yet expanded: " + stats);
        assertEquals(1_000L, stats.searchNanos());
        assertEquals(1L, stats.searchMicros());
        assertEquals(WalkSearchStats.Cap.NONE, stats.cap());
        assertFalse(stats.lengthPruned());
    }

    @Test
    void theExpansionBudgetIsReportedAsTheCap() {
        WalkFixture f = new WalkFixture().floor(0, 0, 30, 0, 64, G);
        WalkResult result = new WalkSearch().find(f.request(0, 64, 0, 30, 64, 0)
            .withBudget(new WalkBudget(5, 1.75, 96, 48, 2, 3)));

        assertEquals("expansion budget", result.reason());
        assertEquals(WalkSearchStats.Cap.MAX_EXPANSIONS, result.stats().cap());
        assertEquals(5, result.stats().expansions());
    }

    @Test
    void theLengthCapIsReportedAsTheCap() {
        // the straight distance is 4, the only way round is ~16 > 1.75 × 4 (as WalkSearchTest)
        WalkFixture f = detourFixture();
        WalkResult result = new WalkSearch().find(f.request(0, 64, 0, 0, 64, 4)
            .withBudget(new WalkBudget(20_000, 1.75, 96, 0, 2, 3)));

        assertEquals("length cap", result.reason());
        assertEquals(WalkSearchStats.Cap.MAX_LENGTH, result.stats().cap());
        assertTrue(result.stats().lengthPruned());
    }

    @Test
    void aFoundPathMayStillHaveHitTheLengthCap() {
        // the detour is ~16 blocks; a cap of 4.5 × 4 = 18 only just fits it, so branches past 18 blocks are dropped
        // on the way although the search arrives
        WalkFixture f = detourFixture();
        WalkResult result = new WalkSearch().find(f.request(0, 64, 0, 0, 64, 4)
            .withBudget(new WalkBudget(20_000, 4.5, 96, 0, 2, 3)));

        assertTrue(result.isFound(), result.toString());
        assertEquals(WalkSearchStats.Cap.NONE, result.stats().cap());
        assertTrue(result.stats().lengthPruned(), "branches past 18 blocks were dropped: " + result.stats());
    }

    @Test
    void anUnreachableTargetEndsWithoutACap() {
        WalkFixture f = new WalkFixture().floor(0, 0, 10, 10, 64, G);
        f.column(8, 5, 65, 68, GridFixture.STONE);
        WalkResult result = new WalkSearch().find(f.request(0, 64, 5, 8, 68, 5)
            .withBudget(new WalkBudget(20_000, 10.0, 400.0, 0.0, 2, 3)));

        assertEquals(WalkResult.Status.NO_PATH, result.status(), result.toString());
        assertEquals(WalkSearchStats.Cap.NONE, result.stats().cap());
        assertEquals(result.expansions(), result.stats().expansions());
    }

    @Test
    void noWalkableStartCountsNothingButTime() {
        WalkFixture f = new WalkFixture().floor(0, 0, 4, 4, 64, G);
        WalkResult result = stepping().find(f.request(40, 64, 40, 2, 64, 2));

        assertEquals("no walkable cell near the start", result.reason());
        assertEquals(0, result.stats().expansions());
        assertEquals(0, result.stats().cells());
        assertEquals(1_000L, result.stats().searchNanos());
    }

    @Test
    void theCountersChangeNoResult() {
        // the same request with two different clocks: the same path, cell by cell, the same cost and expansions
        WalkFixture f = new WalkFixture().floor(0, 0, 24, 12, 64, G).layer(65,
            ".........................",
            ".........................",
            "....#######..............",
            "..........#..............",
            "..........#....#####.....",
            "..........#..............");
        WalkRequest request = f.request(0, 64, 0, 22, 64, 10);
        WalkResult a = new WalkSearch().find(request);
        WalkResult b = stepping().find(request);

        assertTrue(a.isFound(), a.toString());
        WalkPath pa = a.path().orElseThrow();
        WalkPath pb = b.path().orElseThrow();
        assertEquals(pa.size(), pb.size());
        for (int i = 0; i < pa.size(); i++) {
            assertEquals(pa.cell(i), pb.cell(i), "cell " + i);
        }
        assertEquals(pa.cost(), pb.cost(), 0.0);
        assertEquals(a.expansions(), b.expansions());
        assertEquals(a.stats().cells(), b.stats().cells());
        assertEquals(a.toString(), b.toString(), "the result's text has no counters in it");
    }

    @Test
    void aResultWithoutASearchHasNoCounters() {
        WalkResult plain = WalkResult.noPath("test", 3);

        assertSame(WalkSearchStats.NONE, plain.stats());
        WalkSearchStats stats = new WalkSearchStats(3, 7, 5_000L, WalkSearchStats.Cap.NONE, false);
        WalkResult withStats = plain.withStats(stats);
        assertSame(stats, withStats.stats());
        assertEquals(plain.status(), withStats.status());
        assertEquals(plain.reason(), withStats.reason());
        assertEquals(plain.expansions(), withStats.expansions());
        assertSame(WalkSearchStats.NONE, plain.stats(), "the original is unchanged");
    }

    @Test
    void negativeCountersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new WalkSearchStats(-1, 0, 0, WalkSearchStats.Cap.NONE, false));
        assertThrows(NullPointerException.class, () -> new WalkSearchStats(0, 0, 0, null, false));
    }

    private static WalkFixture detourFixture() {
        return new WalkFixture().floor(0, 0, 8, 4, 64, G).layer(65,
            ".........", ".........", "########.", ".........", ".........");
    }
}

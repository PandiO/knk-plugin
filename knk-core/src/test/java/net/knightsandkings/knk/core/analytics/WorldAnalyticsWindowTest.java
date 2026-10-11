package net.knightsandkings.knk.core.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.analytics.WorldAnalyticsBatch;
import net.knightsandkings.knk.core.domain.analytics.WorldAnalyticsBatch.DomainInteraction;

/**
 * KNG-34 link 7 (IMPLEMENTATION_PLAN.md §3.4): a window never crosses a local midnight, closed windows
 * queue (bounded) until drained, failed batches go back in front, oversized windows split.
 */
class WorldAnalyticsWindowTest {

    private static final ZoneId AMSTERDAM = ZoneId.of("Europe/Amsterdam");
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    static final class TestClock extends Clock {
        Instant now;

        TestClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    // 21:50 UTC on 3 Oct = 23:50 in Amsterdam (CEST); local midnight is 22:00 UTC.
    private final TestClock clock = new TestClock(Instant.parse("2026-10-03T21:50:00Z"));

    @Test
    void drain_closesTheWindow_withItsStart_andStartsANewOne() {
        WorldAnalyticsWindow window = new WorldAnalyticsWindow(16, clock, AMSTERDAM, 12);
        window.sample("world", 1, 1);
        window.menuOpened("profile.main");
        window.regionEntered("aldmoor", ALICE);
        clock.advance(Duration.ofMinutes(5));

        List<WorldAnalyticsBatch> batches = window.drain();

        WorldAnalyticsBatch batch = batches.get(0);
        assertEquals(1, batches.size());
        assertEquals(Instant.parse("2026-10-03T21:50:00Z"), batch.windowStart());
        assertEquals(3, batch.rowCount());
        assertTrue(window.currentWindowIsEmpty());
        assertTrue(window.drain().isEmpty(), "an empty window produces no batch");
    }

    @Test
    void theFirstRecordAfterLocalMidnight_closesThePreviousDaysWindow() {
        WorldAnalyticsWindow window = new WorldAnalyticsWindow(16, clock, AMSTERDAM, 12);
        window.sample("world", 0, 0);
        window.regionEntered("aldmoor", ALICE);
        clock.advance(Duration.ofMinutes(15)); // 00:05 local, 4 Oct
        window.sample("world", 0, 0);
        window.regionEntered("aldmoor", ALICE);

        List<WorldAnalyticsBatch> batches = window.drain();

        assertEquals(2, batches.size());
        assertEquals(Instant.parse("2026-10-03T21:50:00Z"), batches.get(0).windowStart());
        assertEquals(Instant.parse("2026-10-03T22:05:00Z"), batches.get(1).windowStart());
        assertEquals("2026-10-04", batches.get(1).windowStart().atZone(AMSTERDAM).toLocalDate().toString());
        // The new day's distinct players start from zero.
        assertEquals(List.of(new DomainInteraction(null, "aldmoor", "enter", 1, 1)), batches.get(1).domainInteractions());
    }

    @Test
    void aFlushJustBeforeMidnight_leavesAnEmptyWindowThatTheNewDayRestarts() {
        WorldAnalyticsWindow window = new WorldAnalyticsWindow(16, clock, AMSTERDAM, 12);
        clock.advance(Duration.ofMinutes(9)); // 23:59 local
        assertTrue(window.drain().isEmpty());
        clock.advance(Duration.ofMinutes(2)); // 00:01 local
        window.sample("world", 0, 0);

        WorldAnalyticsBatch batch = window.drain().get(0);

        assertEquals(Instant.parse("2026-10-03T22:01:00Z"), batch.windowStart());
    }

    @Test
    void theZoneDecidesMidnight() {
        WorldAnalyticsWindow window = new WorldAnalyticsWindow(16, clock, ZoneOffset.UTC, 12);
        window.sample("world", 0, 0);
        clock.advance(Duration.ofMinutes(15)); // 22:05 UTC: same UTC day
        window.sample("world", 0, 0);
        assertEquals(1, window.drain().size());

        window.setZone(AMSTERDAM);
        assertEquals(AMSTERDAM, window.zone());
    }

    @Test
    void requeue_putsFailedBatchesInFront_andTheBoundDropsTheOldest() {
        WorldAnalyticsWindow window = new WorldAnalyticsWindow(16, clock, ZoneOffset.UTC, 2);
        window.sample("world", 0, 0);
        List<WorldAnalyticsBatch> first = window.drain();
        window.requeue(first);
        assertEquals(1, window.pendingBatches());

        window.sample("world", 1, 0);
        List<WorldAnalyticsBatch> both = window.drain();
        assertEquals(first.get(0).batchId(), both.get(0).batchId(), "the retried batch keeps its id and goes first");
        assertEquals(2, both.size());

        window.requeue(both);
        window.sample("world", 2, 0);
        List<WorldAnalyticsBatch> bounded = window.drain();
        assertEquals(2, bounded.size());
        assertEquals(both.get(1).batchId(), bounded.get(0).batchId());
        assertEquals(1, window.droppedBatches());
    }

    @Test
    void anOversizedWindow_isSplitIntoSeveralBatchesOfTheSameWindow() {
        WorldAnalyticsWindow window = new WorldAnalyticsWindow(1, clock, ZoneOffset.UTC, 12, 3);
        for (int x = 0; x < 5; x++) {
            window.sample("world", x, 0);
        }
        window.menuOpened("m");

        List<WorldAnalyticsBatch> batches = window.drain();

        assertEquals(2, batches.size());
        assertEquals(3, batches.get(0).rowCount());
        assertEquals(3, batches.get(1).rowCount());
        assertEquals(1, batches.get(0).menuSteps().size(), "small sections first");
        assertEquals(batches.get(0).windowStart(), batches.get(1).windowStart());
        assertTrue(!batches.get(0).batchId().equals(batches.get(1).batchId()));
    }
}

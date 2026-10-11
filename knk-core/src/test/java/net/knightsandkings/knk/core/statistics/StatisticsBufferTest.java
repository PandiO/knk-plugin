package net.knightsandkings.knk.core.statistics;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.statistics.StatisticsBatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** IMPLEMENTATION_PLAN.md §5.2: aggregation per minute, drain order, splitting, the entry budget. */
class StatisticsBufferTest {

    private static final Instant T0 = Instant.parse("2026-10-03T10:00:05Z");
    private static final UUID BATCH = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    private final StatisticsBuffer buffer = new StatisticsBuffer();

    private StatisticsBatch drain(int max) {
        return buffer.drain(max, T0.plusSeconds(600), () -> BATCH);
    }

    @Test
    void anEmptyBufferDrainsToNull() {
        assertTrue(buffer.isEmpty());
        assertNull(drain(100));
    }

    @Test
    void countersAreSummedPerUserMetricContextAndMinute() {
        buffer.addCounter(7, StatisticsMetric.PVE_KILLS, StatisticsContext.OPEN_WORLD, 1, T0);
        buffer.addCounter(7, StatisticsMetric.PVE_KILLS, StatisticsContext.OPEN_WORLD, 2, T0.plusSeconds(30));
        buffer.addCounter(7, StatisticsMetric.PVE_KILLS, StatisticsContext.SIEGE, 1, T0);
        buffer.addCounter(7, StatisticsMetric.PVE_KILLS, StatisticsContext.OPEN_WORLD, 1, T0.plusSeconds(60));
        buffer.addCounter(8, StatisticsMetric.PVE_KILLS, StatisticsContext.OPEN_WORLD, 1, T0);

        StatisticsBatch batch = drain(100);

        assertEquals(4, batch.counters().size());
        StatisticsBatch.ValueEntry first = batch.counters().get(0);
        assertEquals(7, first.userId());
        assertEquals("pve_kills", first.metric());
        assertEquals("open_world", first.context());
        assertEquals(3, first.value(), 1e-9);
        assertEquals(T0.plusSeconds(30), first.occurredAt(), "the latest contribution");
        assertEquals("siege", batch.counters().get(1).context());
        assertTrue(buffer.isEmpty());
    }

    @Test
    void nonContextualMetricsAreSentWithAnEmptyContextAndContextualOnesDefaultToOpenWorld() {
        buffer.addCounter(7, StatisticsMetric.DISTANCE_FOOT, StatisticsContext.SIEGE, 4, T0);
        buffer.addCounter(7, StatisticsMetric.ARROWS_FIRED, StatisticsContext.NONE, 1, T0);

        StatisticsBatch batch = drain(100);

        assertEquals("", batch.counters().get(0).context());
        assertEquals("open_world", batch.counters().get(1).context());
    }

    @Test
    void recordsKeepTheHighestValue() {
        buffer.addRecord(7, StatisticsMetric.HIGHEST_FALL, StatisticsContext.NONE, 12.5, T0);
        buffer.addRecord(7, StatisticsMetric.HIGHEST_FALL, StatisticsContext.NONE, 30.1, T0.plusSeconds(10));
        buffer.addRecord(7, StatisticsMetric.HIGHEST_FALL, StatisticsContext.NONE, 20, T0.plusSeconds(20));

        StatisticsBatch batch = drain(100);

        assertEquals(1, batch.records().size());
        assertEquals(30.1, batch.records().get(0).value(), 1e-9);
        assertEquals(T0.plusSeconds(10), batch.records().get(0).occurredAt());
    }

    @Test
    void invalidValuesAndUnknownUsersAreIgnored() {
        buffer.addCounter(0, StatisticsMetric.PVE_KILLS, StatisticsContext.OPEN_WORLD, 1, T0);
        buffer.addCounter(7, StatisticsMetric.PVE_KILLS, StatisticsContext.OPEN_WORLD, 0, T0);
        buffer.addCounter(7, StatisticsMetric.PVE_KILLS, StatisticsContext.OPEN_WORLD, Double.NaN, T0);
        buffer.addRecord(7, StatisticsMetric.HIGHEST_FALL, StatisticsContext.NONE, -1, T0);
        buffer.addPvpKill(new StatisticsBatch.PvpKillEntry(7, 7, "open_world", T0));
        assertTrue(buffer.isEmpty());
    }

    @Test
    void theWrongListIsAProgrammingError() {
        assertThrows(IllegalArgumentException.class,
                () -> buffer.addCounter(7, StatisticsMetric.HIGHEST_FALL, StatisticsContext.NONE, 1, T0));
        assertThrows(IllegalArgumentException.class,
                () -> buffer.addRecord(7, StatisticsMetric.DEATHS, StatisticsContext.NONE, 1, T0));
    }

    @Test
    void countersAboveTheEntryLimitAreSplit() {
        buffer.addCounter(7, StatisticsMetric.DISTANCE_FOOT, StatisticsContext.NONE, 250_000, T0);
        assertEquals(3, buffer.size());

        StatisticsBatch batch = drain(100);

        assertEquals(3, batch.counters().size());
        assertEquals(100_000, batch.counters().get(0).value(), 1e-9);
        assertEquals(100_000, batch.counters().get(1).value(), 1e-9);
        assertEquals(50_000, batch.counters().get(2).value(), 1e-9);
    }

    @Test
    void sessionsGoFirstAndWhatDoesNotFitStays() {
        UUID session = UUID.randomUUID();
        buffer.addCounter(7, StatisticsMetric.PVE_KILLS, StatisticsContext.OPEN_WORLD, 1, T0);
        buffer.addDuration(new StatisticsBatch.DurationEntry(session, 7, "active_playtime", T0, T0.plusSeconds(60)));
        buffer.addSession(StatisticsBatch.SessionEntry.start(session, 7, T0));
        buffer.addPvpKill(new StatisticsBatch.PvpKillEntry(7, 8, "open_world", T0));

        StatisticsBatch first = drain(2);
        assertEquals(1, first.sessions().size());
        assertEquals(1, first.durations().size());
        assertEquals(0, first.counters().size());
        assertEquals(2, first.entryCount());

        StatisticsBatch second = drain(2);
        assertEquals(1, second.counters().size());
        assertEquals(1, second.pvpKills().size());
        assertTrue(buffer.isEmpty());
    }

    @Test
    void aSplitCounterCanSpanTwoBatches() {
        buffer.addCounter(7, StatisticsMetric.DISTANCE_FOOT, StatisticsContext.NONE, 150_000, T0);

        StatisticsBatch first = drain(1);
        assertEquals(100_000, first.counters().get(0).value(), 1e-9);
        StatisticsBatch second = drain(1);
        assertEquals(50_000, second.counters().get(0).value(), 1e-9);
        assertTrue(buffer.isEmpty());
    }

    @Test
    void theBudgetNeverExceedsTheApiLimit() {
        for (int i = 0; i < 2_100; i++) {
            buffer.addPvpKill(new StatisticsBatch.PvpKillEntry(7, 8, "open_world", T0));
        }
        assertEquals(StatisticsBatch.MAX_ENTRIES, drain(10_000).entryCount());
        assertEquals(100, drain(10_000).entryCount());
    }

    @Test
    void emptyDurationsAreIgnored() {
        buffer.addDuration(new StatisticsBatch.DurationEntry(UUID.randomUUID(), 7, "afk_time", T0, T0));
        assertTrue(buffer.isEmpty());
    }
}

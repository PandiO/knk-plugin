package net.knightsandkings.knk.core.statistics;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.statistics.StatisticsBatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DESIGN.md §F.2-§F.3, §F.9: sessions, reconnects, unresolved user ids, distance totals. */
class StatisticsSessionsTest {

    private static final Instant T0 = Instant.parse("2026-10-03T10:00:00Z");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-00000000aaaa");

    private final Deque<UUID> keys = new ArrayDeque<>(List.of(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
            UUID.fromString("00000000-0000-0000-0000-000000000003")));
    private final StatisticsBuffer buffer = new StatisticsBuffer();
    private final StatisticsSessions sessions = new StatisticsSessions(buffer, Duration.ofSeconds(300), keys::poll);

    private static Instant at(long seconds) {
        return T0.plusSeconds(seconds);
    }

    private StatisticsBatch drain() {
        return buffer.drain(2_000, at(100_000), UUID::randomUUID);
    }

    @Test
    void aSessionProducesStartDurationsAndEnd() {
        sessions.start(PLAYER, 42, T0);
        sessions.activity(PLAYER, at(100));
        sessions.accrueAll(at(120));
        sessions.end(PLAYER, StatisticsBatch.EndReason.Quit, at(200));

        StatisticsBatch batch = drain();

        UUID key = UUID.fromString("00000000-0000-0000-0000-000000000001");
        assertEquals(List.of(StatisticsBatch.SessionEntry.start(key, 42, T0),
                StatisticsBatch.SessionEntry.end(key, 42, at(200), StatisticsBatch.EndReason.Quit)), batch.sessions());
        assertEquals(List.of(new StatisticsBatch.DurationEntry(key, 42, "active_playtime", T0, at(100)),
                new StatisticsBatch.DurationEntry(key, 42, "active_playtime", at(100), at(200))), batch.durations());
        assertFalse(sessions.hasSession(PLAYER));
    }

    @Test
    void aReconnectIsANewSession() {
        sessions.start(PLAYER, 42, T0);
        sessions.end(PLAYER, StatisticsBatch.EndReason.Kick, at(10));
        sessions.start(PLAYER, 42, at(20));
        sessions.end(PLAYER, StatisticsBatch.EndReason.Quit, at(30));

        StatisticsBatch batch = drain();

        assertEquals(4, batch.sessions().size());
        assertEquals(StatisticsBatch.EndReason.Kick, batch.sessions().get(1).endReason());
        assertTrue(!batch.sessions().get(0).sessionKey().equals(batch.sessions().get(2).sessionKey()));
    }

    @Test
    void aSecondStartEndsTheOpenSessionFirst() {
        sessions.start(PLAYER, 42, T0);
        sessions.start(PLAYER, 42, at(5));

        StatisticsBatch batch = drain();

        assertEquals(StatisticsBatch.SessionType.START, batch.sessions().get(0).type());
        assertEquals(StatisticsBatch.SessionType.END, batch.sessions().get(1).type());
        assertEquals(StatisticsBatch.SessionType.START, batch.sessions().get(2).type());
    }

    @Test
    void serverStopEndsEverySession() {
        UUID other = UUID.randomUUID();
        sessions.start(PLAYER, 42, T0);
        sessions.start(other, 43, T0);
        sessions.endAll(StatisticsBatch.EndReason.ServerStop, at(60));

        StatisticsBatch batch = drain();

        assertEquals(2, batch.sessions().stream().filter(s -> s.endReason() == StatisticsBatch.EndReason.ServerStop).count());
        assertFalse(sessions.hasSession(PLAYER));
    }

    @Test
    void autoAfkIsReportedOnceAndActivityEndsIt() {
        sessions.start(PLAYER, 42, T0);
        assertEquals(List.of(), sessions.checkIdle(at(299)));
        assertEquals(List.of(PLAYER), sessions.checkIdle(at(300)));
        assertEquals(List.of(), sessions.checkIdle(at(400)));
        assertTrue(sessions.isAfk(PLAYER));
        assertTrue(sessions.activity(PLAYER, at(500)));
        assertFalse(sessions.isAfk(PLAYER));
        assertEquals(true, sessions.toggleAfk(PLAYER, at(510)).orElseThrow());
        assertTrue(sessions.toggleAfk(UUID.randomUUID(), at(510)).isEmpty());
    }

    @Test
    void distanceIsTotalledPerModeAndFlushedOnAccrue() {
        sessions.start(PLAYER, 42, T0);
        sessions.addDistance(PLAYER, MovementClassifier.Mode.FOOT, 1.5, at(1));
        sessions.addDistance(PLAYER, MovementClassifier.Mode.SWIM, 2.0, at(2));
        sessions.addDistance(PLAYER, MovementClassifier.Mode.FLYING, 3.0, at(3));
        sessions.addDistance(PLAYER, MovementClassifier.Mode.VEHICLE, 4.0, at(4));
        sessions.accrueAll(at(60));

        StatisticsBatch batch = drain();

        assertEquals(4, batch.counters().size());
        assertEquals("distance.foot", batch.counters().get(0).metric());
        assertEquals(3.5, batch.counters().get(0).value(), 1e-9);
        assertEquals("distance.swim", batch.counters().get(1).metric());
        assertEquals(2.0, batch.counters().get(1).value(), 1e-9);
        assertEquals("", batch.counters().get(0).context());
        assertEquals(at(4), batch.counters().get(0).occurredAt());

        sessions.accrueAll(at(120));
        assertNull(drain(), "totals reset after a flush");
    }

    @Test
    void anUnknownUserIdHoldsEverythingUntilResolved() {
        sessions.start(PLAYER, 0, T0);
        sessions.activity(PLAYER, at(30));
        sessions.addDistance(PLAYER, MovementClassifier.Mode.FOOT, 5, at(31));
        sessions.addRecord(PLAYER, StatisticsMetric.HIGHEST_FALL, StatisticsContext.NONE, 12.5, at(32));
        sessions.accrueAll(at(60));
        assertNull(drain(), "nothing is sent without a user id");
        assertEquals(java.util.Set.of(PLAYER), sessions.unresolvedPlayers());
        assertTrue(sessions.userId(PLAYER).isEmpty());

        assertTrue(sessions.resolve(PLAYER, 42));
        StatisticsBatch batch = drain();

        assertEquals(1, batch.sessions().size());
        assertEquals(T0, batch.sessions().get(0).at(), "the start keeps the join instant");
        assertEquals(42, batch.sessions().get(0).userId());
        assertEquals(1, batch.durations().size());
        assertEquals(1, batch.counters().size());
        assertEquals(1, batch.records().size());
        assertTrue(sessions.unresolvedPlayers().isEmpty());
        assertEquals(42, sessions.userId(PLAYER).getAsInt());
    }

    @Test
    void anEndedUnresolvedSessionIsReleasedWithItsEnd() {
        sessions.start(PLAYER, 0, T0);
        sessions.end(PLAYER, StatisticsBatch.EndReason.Quit, at(90));
        assertNull(drain());

        assertFalse(sessions.resolve(PLAYER, 0));
        assertTrue(sessions.resolve(PLAYER, 42));
        StatisticsBatch batch = drain();

        assertEquals(2, batch.sessions().size());
        assertEquals(StatisticsBatch.SessionType.END, batch.sessions().get(1).type());
        assertEquals(at(90), batch.sessions().get(1).at());
        assertEquals(1, batch.durations().size());
        assertFalse(sessions.resolve(PLAYER, 42), "nothing left");
    }

    @Test
    void factsWithoutASessionAreIgnored() {
        sessions.addDistance(PLAYER, MovementClassifier.Mode.FOOT, 1, T0);
        sessions.addCounter(PLAYER, StatisticsMetric.PVE_KILLS, StatisticsContext.OPEN_WORLD, 1, T0);
        assertFalse(sessions.activity(PLAYER, T0));
        assertNull(drain());
    }
}

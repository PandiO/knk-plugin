package net.knightsandkings.knk.core.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/** Developer decision 2026-10-12: the outage timer resets only after a full recovery. */
class OutageClockTest {

    private static final Instant T0 = Instant.parse("2026-10-12T10:00:00Z");

    @Test
    void theDelayCountsFromTheFirstDrop() {
        OutageClock clock = new OutageClock(null);
        assertTrue(clock.down(T0), "a new outage starts");
        assertFalse(clock.due(T0.plusSeconds(59), 60));
        assertTrue(clock.due(T0.plusSeconds(60), 60));
        assertTrue(clock.due(T0, 0), "0 = immediately");
    }

    @Test
    void aDropBeforeTheBacklogIsSyncedContinuesTheTimer() {
        OutageClock clock = new OutageClock(null);
        clock.down(T0);
        clock.up(T0.plusSeconds(55));
        assertFalse(clock.inOutage(), "no actions while the API is up");
        assertFalse(clock.tick(T0.plusSeconds(56), false));
        assertFalse(clock.down(T0.plusSeconds(57)), "the same outage");
        assertTrue(clock.due(T0.plusSeconds(60), 60), "60 s after the first drop, not after the second");
    }

    @Test
    void aSyncedBacklogResetsTheTimer() {
        OutageClock clock = new OutageClock(null);
        clock.down(T0);
        clock.up(T0.plusSeconds(30));
        assertTrue(clock.tick(T0.plusSeconds(31), true));
        assertEquals(OutageClock.Phase.HEALTHY, clock.phase());
        assertTrue(clock.down(T0.plusSeconds(40)), "a new outage");
        assertFalse(clock.due(T0.plusSeconds(60), 60));
        assertTrue(clock.due(T0.plusSeconds(100), 60));
    }

    @Test
    void aStuckSpoolStillResetsAfterTheCap() {
        OutageClock clock = new OutageClock(null);
        clock.down(T0);
        clock.up(T0.plusSeconds(10));
        assertFalse(clock.tick(T0.plusSeconds(10).plus(OutageClock.RECOVERY_CAP).minusSeconds(1), false));
        assertTrue(clock.tick(T0.plusSeconds(10).plus(OutageClock.RECOVERY_CAP), false));
    }

    @Test
    void aRestartDuringAnOutageContinuesTheTimer() {
        Instant persisted = T0.minusSeconds(600);
        OutageClock down = new OutageClock(persisted);
        assertFalse(down.down(T0), "not a new outage");
        assertTrue(down.due(T0, 300));
        assertEquals(persisted, down.startedAt().orElseThrow());

        OutageClock up = new OutageClock(persisted);
        up.up(T0);
        assertEquals(OutageClock.Phase.RECOVERING, up.phase());
        assertTrue(up.tick(T0.plusSeconds(1), true), "synced after the restart");
    }
}

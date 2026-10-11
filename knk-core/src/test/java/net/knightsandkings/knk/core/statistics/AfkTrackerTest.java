package net.knightsandkings.knk.core.statistics;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DESIGN.md §F.2: automatic/manual AFK, retroactive idle window, pending time on quit. */
class AfkTrackerTest {

    private static final Instant T0 = Instant.parse("2026-10-03T10:00:00Z");
    private static final Duration IDLE = Duration.ofSeconds(300);

    private static Instant at(long seconds) {
        return T0.plusSeconds(seconds);
    }

    private static AfkTracker.Slice active(long from, long to) {
        return new AfkTracker.Slice(at(from), at(to), false);
    }

    private static AfkTracker.Slice afk(long from, long to) {
        return new AfkTracker.Slice(at(from), at(to), true);
    }

    @Test
    void activeTimeIsHandedOutUpToTheLastActivityAndTheRestStaysPending() {
        AfkTracker tracker = new AfkTracker(T0, IDLE);
        tracker.activity(at(50));

        assertEquals(List.of(active(0, 50)), tracker.accrue(at(60)));
        assertEquals(List.of(), tracker.accrue(at(70)), "nothing classified since");

        tracker.activity(at(100));
        assertEquals(List.of(active(50, 100)), tracker.accrue(at(120)));
    }

    @Test
    void reachingTheThresholdMakesTheWholeIdleWindowAfk() {
        AfkTracker tracker = new AfkTracker(T0, IDLE);
        tracker.activity(at(100));

        assertFalse(tracker.advance(at(399)));
        assertTrue(tracker.advance(at(400)));
        assertTrue(tracker.isAfk());
        assertFalse(tracker.isManual());
        assertEquals(at(100), tracker.afkSince());

        assertEquals(List.of(active(0, 100), afk(100, 460)), tracker.accrue(at(460)));
    }

    @Test
    void activityWithinTheThresholdTurnsThePendingWindowActive() {
        AfkTracker tracker = new AfkTracker(T0, IDLE);
        tracker.activity(at(299));
        tracker.activity(at(598));

        assertEquals(List.of(active(0, 598)), tracker.accrue(at(600)));
        assertFalse(tracker.isAfk());
    }

    @Test
    void activityEndsAfkEvenWhenTheThresholdWasOnlyCrossedNow() {
        AfkTracker tracker = new AfkTracker(T0, IDLE);
        // no check ran in between: the activity at 500 first discovers AFK since 0, then ends it
        assertTrue(tracker.activity(at(500)));
        assertFalse(tracker.isAfk());
        assertEquals(List.of(afk(0, 500)), tracker.accrue(at(510)));
    }

    @Test
    void manualAfkStartsNowAndCountsThePendingWindowAsActive() {
        AfkTracker tracker = new AfkTracker(T0, IDLE);
        tracker.activity(at(10));

        assertTrue(tracker.toggleManual(at(40)));
        assertTrue(tracker.isManual());
        assertEquals(at(40), tracker.afkSince());
        assertEquals(List.of(active(0, 40), afk(40, 100)), tracker.accrue(at(100)));

        assertFalse(tracker.toggleManual(at(130)), "a second /afk leaves AFK");
        assertNull(tracker.afkSince());
        assertEquals(List.of(afk(100, 130)), tracker.accrue(at(131)));
    }

    @Test
    void activityEndsManualAfk() {
        AfkTracker tracker = new AfkTracker(T0, IDLE);
        tracker.toggleManual(at(5));
        assertTrue(tracker.activity(at(20)));
        assertFalse(tracker.isAfk());
        assertEquals(List.of(active(0, 5), afk(5, 20)), tracker.accrue(at(20)));
    }

    @Test
    void quitCountsPendingTimeAsActive() {
        AfkTracker tracker = new AfkTracker(T0, IDLE);
        tracker.activity(at(100));
        assertEquals(List.of(active(0, 250)), tracker.quit(at(250)));
    }

    @Test
    void quitWhileAfkEndsWithAfkTime() {
        AfkTracker tracker = new AfkTracker(T0, IDLE);
        assertEquals(List.of(afk(0, 900)), tracker.quit(at(900)));
    }

    @Test
    void slicesLongerThanADayAreSplit() {
        AfkTracker tracker = new AfkTracker(T0, Duration.ZERO);
        tracker.activity(at(200_000));
        List<AfkTracker.Slice> slices = tracker.accrue(at(200_000));
        assertEquals(List.of(active(0, 86_400), active(86_400, 172_800), active(172_800, 200_000)), slices);
    }

    @Test
    void zeroIdleMeansOnlyManualAfk() {
        AfkTracker tracker = new AfkTracker(T0, Duration.ZERO);
        assertFalse(tracker.advance(at(100_000)));
        assertFalse(tracker.isAfk());
    }

    @Test
    void anEarlierInstantDoesNotMoveTimeBackwards() {
        AfkTracker tracker = new AfkTracker(T0, IDLE);
        tracker.activity(at(100));
        tracker.activity(at(50));
        assertEquals(at(100), tracker.lastActivity());
        assertEquals(List.of(active(0, 100)), tracker.accrue(at(100)));
    }
}

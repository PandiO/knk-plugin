package net.knightsandkings.knk.core.statistics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Open-world killstreak (DESIGN.md §F.7, D2, L1-11): increments on kills, resets on death and quit. */
class KillstreakTrackerTest {

    private final KillstreakTracker tracker = new KillstreakTracker();
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @Test
    void killsIncrementTheStreakPerPlayer() {
        assertEquals(1, tracker.kill(alice));
        assertEquals(2, tracker.kill(alice));
        assertEquals(1, tracker.kill(bob));
        assertEquals(3, tracker.kill(alice));
        assertEquals(3, tracker.current(alice));
        assertEquals(1, tracker.current(bob));
    }

    @Test
    void aDeathResetsOnlyThatPlayersStreak() {
        tracker.kill(alice);
        tracker.kill(alice);
        tracker.kill(bob);

        tracker.death(alice);

        assertEquals(0, tracker.current(alice));
        assertEquals(1, tracker.current(bob));
        assertEquals(1, tracker.kill(alice), "the streak starts over after dying");
    }

    @Test
    void quittingResetsTheStreak() {
        tracker.kill(alice);
        tracker.kill(alice);

        tracker.quit(alice);

        assertEquals(0, tracker.current(alice));
        assertEquals(0, tracker.size());
    }

    @Test
    void nullPlayersAreIgnored() {
        assertEquals(0, tracker.kill(null));
        tracker.death(null);
        tracker.quit(null);
        assertEquals(0, tracker.current(null));
        assertEquals(0, tracker.size());
    }
}

package net.knightsandkings.knk.core.menu;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** InventoryMenu Phase 9, E4: live-repaint scheduling (batched per tick, never overlapping). */
class MenuRefreshScheduleTest {

    private final MenuRefreshSchedule schedule = new MenuRefreshSchedule();
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @Test
    void aMenuIsDueEveryPeriodAfterItsLastCompletedRender() {
        schedule.track(alice, 20, 100);

        assertTrue(schedule.due(119).isEmpty());
        assertEquals(List.of(alice), schedule.due(120));
        schedule.completed(alice, 121);
        assertTrue(schedule.due(140).isEmpty());
        assertEquals(List.of(alice), schedule.due(141));
    }

    @Test
    void allDueMenusAreReturnedInTheSameTick() {
        schedule.track(alice, 20, 0);
        schedule.track(bob, 10, 5);

        List<UUID> due = schedule.due(20);

        assertEquals(2, due.size());
        assertTrue(due.containsAll(List.of(alice, bob)));
    }

    @Test
    void anInFlightRenderIsNeverStartedAgainUntilItCompletes() {
        schedule.track(alice, 5, 0);

        assertEquals(List.of(alice), schedule.due(5));
        assertTrue(schedule.due(50).isEmpty(), "slow async fetch still in flight");
        schedule.completed(alice, 51);
        assertEquals(List.of(alice), schedule.due(56));
    }

    @Test
    void markStaleMakesEvenANonRefreshingMenuDueOnTheNextTick() {
        schedule.track(alice, 0, 0);
        assertTrue(schedule.due(10_000).isEmpty(), "no auto-refresh configured");

        schedule.markStale(alice);
        assertEquals(List.of(alice), schedule.due(10_001));
        schedule.completed(alice, 10_001);
        assertTrue(schedule.due(10_002).isEmpty(), "stale flag consumed");
    }

    @Test
    void markStaleDuringAFlightIsHonouredRightAfter() {
        schedule.track(alice, 0, 0);
        schedule.markStale(alice);
        assertEquals(List.of(alice), schedule.due(1));

        schedule.markStale(alice);
        assertTrue(schedule.due(2).isEmpty(), "still in flight");
        schedule.completed(alice, 3);
        assertEquals(List.of(alice), schedule.due(4));
    }

    @Test
    void untrackedMenusAreIgnored() {
        schedule.markStale(alice);
        schedule.completed(alice, 1);
        assertFalse(schedule.isTracked(alice));
        schedule.track(alice, 1, 0);
        schedule.untrack(alice);
        assertTrue(schedule.due(100).isEmpty());
        assertEquals(0, schedule.trackedCount());
    }
}

package net.knightsandkings.knk.core.npc.nav;

import static net.knightsandkings.knk.core.npc.nav.FakeNavWorld.floor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.npc.nav.RouteFollower.Kind;
import net.knightsandkings.knk.core.npc.nav.RouteFollower.Stretch;

class RouteFollowerTest {

    private final FakeNavWorld world = new FakeNavWorld();

    private static RouteFollower straight(double length) {
        return new RouteFollower(List.of(floor(0.5, 64, 0.5), floor(0.5 + length, 64, 0.5)));
    }

    @Test
    void aRouteNeedsAPoint() {
        assertThrows(IllegalArgumentException.class, () -> new RouteFollower(List.of()));
    }

    @Test
    void handsOutOneStretchAtATimeAsFeetWaypointsAtBlockCentres() {
        RouteFollower follower = straight(100);

        Stretch first = follower.next(world, 32, 64);

        assertEquals(Kind.WAYPOINTS, first.kind());
        NavPoint last = first.waypoints().get(first.waypoints().size() - 1);
        assertTrue(last.x() > 25 && last.x() < 36, "about 32 blocks, was " + last.x());
        for (NavPoint p : first.waypoints()) {
            assertEquals(65, p.y(), 1e-9, "feet, one above the floor");
            assertEquals(0.5, p.x() - Math.floor(p.x()), 1e-9, "block centre");
        }
        assertTrue(follower.hasMore());
        assertEquals(follower.sentEndPoint().blockX(), last.blockX());
    }

    @Test
    void theStretchesTogetherCoverTheWholeLineInOrder() {
        RouteFollower follower = straight(100);
        double lastX = -1;
        int stretches = 0;
        while (follower.hasMore()) {
            Stretch stretch = follower.next(world, 32, 64);
            for (NavPoint p : stretch.waypoints()) {
                assertTrue(p.x() > lastX, "waypoints go forward");
                lastX = p.x();
            }
            stretches++;
        }
        assertEquals(100.5, lastX, 1e-9);
        assertEquals(4, stretches);
        assertEquals(Kind.DONE, follower.next(world, 32, 64).kind());
    }

    @Test
    void floorsFollowTheStairsStepByStep() {
        world.floor = (x, z) -> 64 + Math.max(0, Math.min(10, x - 5));
        // the road line only has its ends: 64 at x 0, 74 at x 20
        RouteFollower follower = new RouteFollower(List.of(floor(0.5, 64, 0.5), floor(20.5, 74, 0.5)));

        Stretch stretch = follower.next(world, 32, 64);

        double previous = 65;
        for (NavPoint p : stretch.waypoints()) {
            assertEquals(world.floor.applyAsInt(p.blockX(), 0) + 1, p.y(), 1e-9, "on the step at x " + p.blockX());
            assertTrue(Math.abs(p.y() - previous) <= HopPlanner.CONTINUITY_RANGE, "no jump between waypoints");
            previous = p.y();
        }
    }

    @Test
    void continuityHoldsAcrossStretchesOnStackedFlights() {
        // a lower flight at 50 everywhere and an upper flight at 56 over x 10..40; the line's heights say 55
        world.floor = (x, z) -> 50;
        for (int x = 10; x <= 40; x++) {
            world.addFloor(x, 56, 0);
        }
        RouteFollower follower = new RouteFollower(List.of(floor(0.5, 55, 0.5), floor(40.5, 55, 0.5)));

        Stretch first = follower.next(world, 16, 50);
        Stretch second = follower.next(world, 16, 99); // the NPC's own floor is ignored: it follows straight on

        for (NavPoint p : first.waypoints()) {
            assertEquals(51, p.y(), 1e-9);
        }
        for (NavPoint p : second.waypoints()) {
            assertEquals(51, p.y(), 1e-9, "the second stretch stays on the flight the first ended on");
        }
    }

    @Test
    void aStretchInAnUnloadedChunkHandsNothingOutAndCanBeAskedAgain() {
        RouteFollower follower = straight(100);
        world.loaded = (x, z) -> x < 16;

        assertEquals(Kind.UNLOADED, follower.next(world, 32, 64).kind());
        assertTrue(follower.hasMore());

        world.loaded = (x, z) -> true;
        Stretch stretch = follower.next(world, 32, 64);
        assertEquals(Kind.WAYPOINTS, stretch.kind());
        assertEquals(0.5, stretch.waypoints().get(0).x(), 1e-9, "from the start: nothing was skipped");
    }

    @Test
    void progressIsGettingFurtherAlongTheLineNotJustMoving() {
        RouteFollower follower = straight(100);
        follower.next(world, 32, 64);

        assertTrue(follower.progressed(new NavPoint(FakeNavWorld.WORLD, 3.5, 65, 0.5)));
        assertFalse(follower.progressed(new NavPoint(FakeNavWorld.WORLD, 3.4, 65, 2.0)), "moved, but no further along");
        assertFalse(follower.progressed(new NavPoint(FakeNavWorld.WORLD, 1.0, 65, 0.5)), "going back is no progress");
        assertTrue(follower.progressed(new NavPoint(FakeNavWorld.WORLD, 9.0, 65, 0.5)));
    }

    @Test
    void theNearestPointIsOnTheNpcsOwnFlight() {
        // the line climbs a U-turn: out along z 0 at 50..54, back along z 2 at 56..60
        RouteFollower follower = new RouteFollower(List.of(floor(0.5, 50, 0.5), floor(8.5, 54, 0.5),
            floor(8.5, 56, 2.5), floor(0.5, 60, 2.5)));
        follower.next(world, 64, 50);

        int lower = follower.nearestIndex(new NavPoint(FakeNavWorld.WORLD, 4.5, 53, 1.4));
        assertTrue(follower.line().get(lower).y() < 55, "feet at 53 belong to the lower flight");
    }

    @Test
    void recoveryAimsAFewPointsAheadAndTheLineGoesOnAfterIt() {
        RouteFollower follower = straight(100);
        follower.next(world, 32, 64);
        NavPoint stuckAt = new NavPoint(FakeNavWorld.WORLD, 10.5, 65, 0.5);

        NavPoint target = follower.recoveryTarget(world, stuckAt);

        assertTrue(target.x() > stuckAt.x() + 3 && target.x() < stuckAt.x() + 8, "a few blocks further, was " + target.x());
        assertEquals(64, target.y(), 1e-9, "a floor point");
        Stretch next = follower.next(world, 32, 64);
        assertTrue(next.waypoints().get(0).x() > target.x() - 1e-9, "the next stretch starts after the recovery target");
        assertFalse(follower.progressed(stuckAt), "the stuck spot is behind the recovery target");
    }
}

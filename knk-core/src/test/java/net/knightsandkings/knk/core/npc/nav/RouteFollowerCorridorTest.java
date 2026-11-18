package net.knightsandkings.knk.core.npc.nav;

import static net.knightsandkings.knk.core.npc.nav.FakeNavWorld.floor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.navigation.shape.CorridorShaper;
import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.npc.nav.RouteFollower.Kind;
import net.knightsandkings.knk.core.npc.nav.RouteFollower.Stretch;

/**
 * Corridor steering in the route follower (implementation plan 4c.3 decisions 1 and 4, link M2): with the key off a
 * stretch is today's; with it on the stretch is shaped first, shared between NPCs on the same stretch, and a
 * chokepoint asks for the smaller waypoint margin.
 */
class RouteFollowerCorridorTest {

    private final FakeNavWorld world = new FakeNavWorld();
    private long tick;
    private final CorridorShaper shaper = new CorridorShaper(() -> tick, null);
    private final RouteFollower.Steering steering = new RouteFollower.Steering(shaper,
        new CorridorShaper.Options(4, 0.8));

    /** A 3-wide road (z 0..2) between walls, the road line along its north edge (z 0.5). */
    private RouteFollower roadAlongTheNorthWall() {
        for (int x = -6; x <= 40; x++) {
            world.addWall(x, 64, -1);
            world.addWall(x, 64, 3);
        }
        return new RouteFollower(List.of(floor(0.5, 64, 0.5), floor(39.5, 64, 0.5))); // longer than a stretch
    }

    @Test
    void offTheStretchIsTodaysWithItsWaypointsAtBlockCentres() {
        Stretch stretch = roadAlongTheNorthWall().next(world, 32, 64, 1.0, null);

        assertEquals(Kind.WAYPOINTS, stretch.kind());
        assertEquals(0, stretch.chokepoints());
        for (NavPoint p : stretch.waypoints()) {
            assertEquals(0.5, p.z(), 1e-9, "on the line, at a block centre: " + p);
        }
        assertEquals(0, shaper.shaped());
    }

    @Test
    void onTheStretchKeepsTheClearanceAndIsStraightened() {
        RouteFollower follower = roadAlongTheNorthWall();

        Stretch stretch = follower.next(world, 32, 64, 1.0, steering);

        assertEquals(Kind.WAYPOINTS, stretch.kind());
        for (NavPoint p : stretch.waypoints()) {
            assertTrue(p.z() >= 0.8 - 1e-6 && p.z() <= 2.2 + 1e-6, "0.8 off both walls: " + p);
            assertEquals(65, p.y(), 1e-9, "feet on the road's floor");
        }
        assertTrue(stretch.waypoints().size() <= 4, "straightened: " + stretch.waypoints());
        assertTrue(follower.hasMore() || follower.nextIndex() > 0);
    }

    @Test
    void aSecondNpcOnTheSameStretchGetsTheSharedShape() {
        Stretch first = roadAlongTheNorthWall().next(world, 32, 64, 1.0, steering);
        Stretch second = new RouteFollower(List.of(floor(0.5, 64, 0.5), floor(39.5, 64, 0.5)))
            .next(world, 32, 64, 1.0, steering);

        assertEquals(first.waypoints(), second.waypoints());
        assertEquals(1, shaper.shaped());
        assertEquals(1, shaper.shared());
    }

    @Test
    void aDoorwayIsCountedAndAsksForTheSmallerMargin() {
        // a wall across the road at x 10 with a one-block door at z 1; the road 5 wide (z -1..3)
        for (int z = -6; z <= 8; z++) {
            if (z != 1) {
                world.addWall(10, 64, z);
            }
        }
        RouteFollower follower = new RouteFollower(List.of(floor(0.5, 64, 1.5), floor(20.5, 64, 1.5)));

        Stretch stretch = follower.next(world, 32, 64, 1.0, steering);

        assertEquals(1, stretch.chokepoints());
        NpcNavigationService.Settings settings = NpcNavigationService.Settings.defaults();
        assertEquals(CorridorShaper.CHOKE_MARGIN, settings.marginFor(stretch), 1e-9);
        assertEquals(settings.waypointMargin(), settings.marginFor(new Stretch(Kind.WAYPOINTS, List.of())), 1e-9);
        boolean inDoor = false;
        for (NavPoint p : stretch.waypoints()) {
            inDoor |= Math.abs(p.x() - 10.5) < 0.11 && Math.abs(p.z() - 1.5) < 0.11;
        }
        assertTrue(inDoor, "a waypoint in the door's middle: " + stretch.waypoints());
    }

    @Test
    void theKeysAreOffByDefaultWithTheDecidedValues() {
        NpcNavigationService.Settings settings = NpcNavigationService.Settings.defaults();

        assertEquals(false, settings.corridorSteering());
        assertEquals(4.0, settings.corridorHalfWidth());
        assertEquals(0.8, settings.wallClearance());
    }
}

package net.knightsandkings.knk.core.npc.nav;

import static net.knightsandkings.knk.core.npc.nav.FakeNavWorld.WORLD;
import static net.knightsandkings.knk.core.npc.nav.FakeNavWorld.floor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Corner clearance and the shortcut check (implementation plan 4b.2 items 1, 3 and 4; test plan item 16.1 and 16.2):
 * a route east along z = 0.5, then north (-z) along x = 10.5. The inside of that left turn is the north-west quadrant.
 */
class CornerClearanceTest {

    private final FakeNavWorld world = new FakeNavWorld();

    private RouteFollower turn() {
        return new RouteFollower(List.of(floor(0.5, 64, 0.5), floor(10.5, 64, 0.5), floor(10.5, 64, -10.5)));
    }

    /** A building in the inside corner: walls along z = -1 up to x = 9 and along x = 9 from z = -1 north. */
    private void buildingInTheCorner() {
        for (int x = 0; x <= 9; x++) {
            world.addWall(x, 64, -1);
        }
        for (int z = -12; z <= -1; z++) {
            world.addWall(9, 64, z);
        }
    }

    private static double distanceToWalls(FakeNavWorld world, NavPoint feet) {
        double best = Double.MAX_VALUE;
        for (String wall : world.walls) {
            String[] p = wall.split(",");
            int wx = Integer.parseInt(p[0]);
            int wz = Integer.parseInt(p[2]);
            double dx = Math.max(Math.max(wx - feet.x(), 0), feet.x() - (wx + 1));
            double dz = Math.max(Math.max(wz - feet.z(), 0), feet.z() - (wz + 1));
            best = Math.min(best, Math.hypot(dx, dz));
        }
        return best;
    }

    @Test
    void anInsideCornerMovesTheTurnsWaypointsOutByTheClearance() {
        buildingInTheCorner();
        RouteFollower follower = turn();

        List<NavPoint> waypoints = follower.next(world, 64, 64, 1.0).waypoints();

        boolean sawCorner = false;
        for (NavPoint p : waypoints) {
            if (follower.corners().nearTurn(follower.indexNearest(HopPlanner.floor(p)))) {
                assertTrue(distanceToWalls(world, p) >= 1.0 - 1e-9, "a turn waypoint keeps the clearance: " + p);
            }
            sawCorner |= p.blockX() >= 10 && p.blockZ() >= 0;
        }
        assertTrue(sawCorner, "the corner square is walked into, then the NPC turns: " + waypoints);
        for (NavPoint p : waypoints) {
            assertEquals(65, p.y(), 1e-9, "floor continuity: same height");
            assertEquals(0.5, p.x() - Math.floor(p.x()), 1e-9, "block centres");
        }
    }

    @Test
    void withoutClearanceTheWaypointsBrushTheWall() {
        buildingInTheCorner();
        List<NavPoint> waypoints = turn().next(world, 64, 64, 0).waypoints();

        assertTrue(waypoints.stream().anyMatch(p -> distanceToWalls(world, p) < 1.0), "the old line hugs the corner");
    }

    @Test
    void aTwoWideRoadKeepsTheWaypointsAsFarOutAsItCanAndAOneWideOneLeavesThem() {
        buildingInTheCorner();
        for (int x = 0; x <= 12; x++) {
            world.addWall(x, 64, 2); // the outer side of the incoming road: cells z 0 and 1 are the road
        }
        for (int z = -12; z <= 2; z++) {
            world.addWall(12, 64, z); // the outer side of the outgoing road: cells x 10 and 11
        }
        RouteFollower follower = turn();

        List<NavPoint> waypoints = follower.next(world, 64, 64, 1.0).waypoints();

        for (NavPoint p : waypoints) {
            assertTrue(p.blockZ() <= 1 && p.blockX() <= 11, "never into the outer wall: " + p);
        }
        assertTrue(waypoints.stream().anyMatch(p -> p.blockX() == 11 || p.blockZ() == 1), "moved out where it could");

        FakeNavWorld narrow = new FakeNavWorld();
        for (String wall : world.walls) {
            narrow.walls.add(wall);
        }
        for (int x = 0; x <= 11; x++) {
            narrow.addWall(x, 64, 1);
        }
        for (int z = -12; z <= 1; z++) {
            narrow.addWall(11, 64, z);
        }
        RouteFollower one = turn();
        List<NavPoint> oneWide = one.next(narrow, 64, 64, 1.0).waypoints();
        for (NavPoint p : oneWide) {
            assertTrue(p.blockZ() <= 0 && p.blockX() <= 10, "a 1-wide road leaves the waypoints where they are: " + p);
        }
    }

    @Test
    void aStraightStretchAlongAWallIsUntouched() {
        for (int x = 0; x <= 40; x++) {
            world.addWall(x, 64, -1);
        }
        RouteFollower follower = new RouteFollower(List.of(floor(0.5, 64, 0.5), floor(40.5, 64, 0.5)));

        List<NavPoint> with = follower.next(world, 64, 64, 1.0).waypoints();

        for (NavPoint p : with) {
            assertEquals(0, p.blockZ(), "a road line may run along a wall: " + p);
        }
    }

    @Test
    void aMovedWaypointKeepsItsRegions() {
        buildingInTheCorner();
        // the cells south of the road (z >= 1) are another region: the waypoint may not move there
        world.regionsByColumn = (x, z) -> z >= 1 || x >= 11 ? java.util.Set.of("market") : java.util.Set.of();
        RouteFollower follower = turn();

        for (NavPoint p : follower.next(world, 64, 64, 1.0).waypoints()) {
            assertTrue(p.blockZ() <= 0 && p.blockX() <= 10, "never into a region the line point is not in: " + p);
        }
    }

    /**
     * The #491 stair corner, flattened: a ledge one block wide along z 0 (x 0..5) turning north along x 5, a 7-block
     * drop everywhere else (floor 57). The corner point stays in the stretch and the diagonal past it is refused.
     */
    @Test
    void theStairCornerKeepsItsCornerPointAndTheShortcutIsRefused() {
        world.floor = (x, z) -> (z == 0 && x >= 0 && x <= 5) || (x == 5 && z <= 0 && z >= -6) ? 64 : 57;
        RouteFollower follower = new RouteFollower(List.of(floor(0.5, 64, 0.5), floor(5.5, 64, 0.5),
            floor(5.5, 64, -5.5)));

        List<NavPoint> waypoints = follower.next(world, 64, 64, 1.0).waypoints();

        assertTrue(waypoints.stream().anyMatch(p -> p.blockX() == 5 && p.blockZ() == 0), "the corner point: " + waypoints);
        for (NavPoint p : waypoints) {
            assertEquals(65, p.y(), 1e-9, "never a waypoint over the drop: " + p);
        }
        NavPoint before = new NavPoint(WORLD, 3.5, 65, 0.5);
        NavPoint after = new NavPoint(WORLD, 5.5, 65, -1.5);
        assertFalse(NavGeometry.straightWalkable(world, before, after), "the diagonal crosses air");
        assertTrue(NavGeometry.straightWalkable(world, before, new NavPoint(WORLD, 5.5, 65, 0.5)), "along the ledge");
    }

    @Test
    void aFullBlockRiseNeedsThreeBlocksOfHeadroomAtTheTakeOff() {
        world.headroom.put("0,64,0", 2.5); // the landing under an overhang (1433 44 -517)
        world.addFloor(1, 65, 0);

        assertFalse(NavGeometry.jumpFits(world, floor(0.5, 64, 0.5), floor(1.5, 65, 0.5)));
        assertTrue(NavGeometry.jumpFits(world, floor(0.5, 64, 0.5), floor(-0.5, 64, 0.5)), "level is fine");
        assertTrue(NavGeometry.jumpFits(world, floor(2.5, 64, 0.5), floor(1.5, 65, 0.5)), "3 blocks free elsewhere");
    }
}

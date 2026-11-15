package net.knightsandkings.knk.core.npc.nav;

import static net.knightsandkings.knk.core.npc.nav.FakeNavWorld.floor;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * A 90° turn at a ledge (PandiO/knk-workspace#592, the #491 stair corner at 1411 73 -537): a 2-wide flight comes south
 * (z rising) along x 1409..1410, turns east on the corner square (x 1409..1410, z -536..-535) and climbs on east along
 * z -536..-535. The ground is 7 blocks below everything else, so the cell (1411, -537) on the inside of the turn is a
 * drop, not a wall. Road lines that cut the corner put line points over that drop.
 */
class LedgeCornerTest {

    private static final int GROUND = 66;

    private final FakeNavWorld world = new FakeNavWorld();

    LedgeCornerTest() {
        world.floor = (x, z) -> {
            if ((x == 1409 || x == 1410) && z >= -545 && z <= -539) {
                return 72;                                  // the flight's lower part
            }
            if ((x == 1409 || x == 1410) && z >= -538 && z <= -535) {
                return 73;                                  // the slab step and the corner square
            }
            if (x == 1411 && (z == -536 || z == -535)) {
                return 73;
            }
            if (x >= 1412 && x <= 1420 && (z == -536 || z == -535)) {
                return 74 + (x - 1412) / 2;                 // the flight east, a step every other block
            }
            return GROUND;                                  // the drop all round
        };
    }

    /** The line as the trace suggests it: its segment after the bend runs diagonally across the drop. */
    private static List<NavPoint> cuttingLine() {
        return List.of(floor(1410.0, 72, -545.0), floor(1410.0, 73, -538.0), floor(1418.0, 77, -535.5));
    }

    /** A line through the cells' centres (as the L3 corner scene's), the corner on the outer column. */
    private static List<NavPoint> centreLine() {
        return List.of(floor(1409.5, 72, -544.5), floor(1409.5, 73, -535.5), floor(1418.5, 77, -535.5));
    }

    @Test
    void theCuttingLineCrossesTheDrop() {
        RouteFollower follower = new RouteFollower(cuttingLine());
        boolean overDrop = follower.line().stream()
            .anyMatch(p -> world.floor.applyAsInt(p.blockX(), p.blockZ()) == GROUND);
        assertTrue(overDrop, "the geometry this test is about: a line point over the drop");
    }

    @Test
    void noWaypointDropsToTheGroundBesideTheTurn() {
        for (double clearance : new double[] {0, 1.0}) {
            for (List<NavPoint> line : List.of(cuttingLine(), centreLine())) {
                RouteFollower follower = new RouteFollower(line);
                List<NavPoint> waypoints = all(follower, clearance);
                for (NavPoint p : waypoints) {
                    assertTrue(p.blockY() > GROUND + 1, "a waypoint on the ground below the ledge: " + p + " in " + waypoints);
                }
            }
        }
    }

    @Test
    void everyStepBetweenWaypointsIsAStraightWalkOnTheStair() {
        for (double clearance : new double[] {0, 1.0}) {
            for (List<NavPoint> line : List.of(cuttingLine(), centreLine())) {
                List<NavPoint> waypoints = all(new RouteFollower(line), clearance);
                for (int i = 1; i < waypoints.size(); i++) {
                    assertTrue(NavGeometry.straightWalkable(world, waypoints.get(i - 1), waypoints.get(i)),
                        "a step across the drop: " + waypoints.get(i - 1) + " -> " + waypoints.get(i) + " in "
                            + waypoints + " (clearance " + clearance + ")");
                }
            }
        }
    }

    @Test
    void theTurnGoesThroughTheCornerSquare() {
        List<NavPoint> waypoints = all(new RouteFollower(cuttingLine()), 1.0);
        boolean corner = waypoints.stream().anyMatch(p -> (p.blockX() == 1409 || p.blockX() == 1410)
            && (p.blockZ() == -536 || p.blockZ() == -535));
        assertTrue(corner, "the NPC walks into the corner square before it turns: " + waypoints);
    }

    @Test
    void cornerClearanceKeepsTheTurnAwayFromTheDropOnItsInside() {
        RouteFollower follower = new RouteFollower(centreLine());
        List<NavPoint> waypoints = all(follower, 1.0);
        List<NavPoint> bare = all(new RouteFollower(centreLine()), 0);
        // the inside of the south-then-east turn is north-east of the corner square: the drop cells x >= 1411, z <= -537
        assertTrue(bare.stream().anyMatch(p -> insideDrop(p) < 1.0), "without clearance the turn brushes the drop: " + bare);
        for (NavPoint p : waypoints) {
            if (follower.corners().nearTurn(follower.indexNearest(HopPlanner.floor(p)))) {
                assertTrue(insideDrop(p) >= 1.0 - 1e-9, "a turn waypoint keeps a block from the drop: " + p + " in " + waypoints);
            }
        }
    }

    /** The distance from feet to the nearest drop cell on the inside of the turn. */
    private double insideDrop(NavPoint feet) {
        double best = Double.MAX_VALUE;
        for (int x = 1411; x <= 1415; x++) {
            for (int z = -541; z <= -537; z++) {
                if (world.floor.applyAsInt(x, z) != GROUND) {
                    continue;
                }
                double dx = Math.max(Math.max(x - feet.x(), 0), feet.x() - (x + 1));
                double dz = Math.max(Math.max(z - feet.z(), 0), feet.z() - (z + 1));
                best = Math.min(best, Math.hypot(dx, dz));
            }
        }
        return best;
    }

    /** Every stretch of the line, as the mover gets them. */
    private List<NavPoint> all(RouteFollower follower, double clearance) {
        List<NavPoint> out = new java.util.ArrayList<>();
        int floorY = 72;
        while (follower.hasMore()) {
            RouteFollower.Stretch s = follower.next(world, 6, floorY, clearance);
            if (s.kind() != RouteFollower.Kind.WAYPOINTS) {
                break;
            }
            for (NavPoint p : s.waypoints()) {
                if (out.isEmpty() || !out.get(out.size() - 1).equals(p)) {
                    out.add(p);
                }
            }
            floorY = out.get(out.size() - 1).blockY() - 1;
        }
        return out;
    }
}

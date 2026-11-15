package net.knightsandkings.knk.core.npc.nav.corridor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.npc.nav.LocalLeg;
import net.knightsandkings.knk.core.npc.nav.NavGeometry;
import net.knightsandkings.knk.core.npc.nav.NavPoint;
import net.knightsandkings.knk.core.roads.walk.CellAccess;

/**
 * K&K's own local leg search with the corridor's clearance (implementation plan 4c.4 item 6.2, link M6): the margin past
 * a jagged wall, a one-block door, floor continuity of the smoothed leg (L5's rule), a region the NPC may not enter, the
 * cell budget.
 */
class LocalLegSearchTest {

    private static final double WALL_CLEARANCE = 0.8;
    private static final LocalLeg OPEN = new LocalLeg(CellAccess.OPEN, -100, 0, -100, 100, 255, 100, true);

    private final GridWorld world = new GridWorld();

    private NavPoint floor(double x, double z) {
        NavPoint feet = world.feet(x, z);
        return new NavPoint(feet.world(), feet.x(), feet.y() - 1, feet.z());
    }

    private LocalLegSearch.Result find(double fx, double fz, double tx, double tz, LocalLeg leg) {
        return new LocalLegSearch().find(world, world.feet(fx, fz), floor(tx, tz), leg, WALL_CLEARANCE);
    }

    /** The walked polyline: the NPC's feet, then the waypoints. */
    private static List<NavPoint> walked(NavPoint from, List<NavPoint> waypoints) {
        List<NavPoint> out = new ArrayList<>();
        out.add(from);
        out.addAll(waypoints);
        return out;
    }

    /** The least clearance along a polyline, sampled every 0.1 block, leaving out {@code skip} blocks at both ends. */
    private static double leastClearance(Corridor c, List<NavPoint> line, double skip) {
        double total = 0;
        for (int i = 1; i < line.size(); i++) {
            total += line.get(i - 1).horizontalDistance(line.get(i));
        }
        double least = Double.MAX_VALUE;
        double at = 0;
        for (int i = 1; i < line.size(); i++) {
            NavPoint a = line.get(i - 1);
            NavPoint b = line.get(i);
            double len = a.horizontalDistance(b);
            int n = Math.max(1, (int) Math.ceil(len / 0.1));
            for (int k = 0; k <= n; k++) {
                double s = at + len * k / n;
                if (s < skip || s > total - skip) {
                    continue;
                }
                double x = a.x() + (b.x() - a.x()) * k / n;
                double z = a.z() + (b.z() - a.z()) * k / n;
                least = Math.min(least, c.clearanceAt(x, z));
            }
            at += len;
        }
        return least;
    }

    @Test
    void aLegPastAJaggedWallKeepsTheMarginWhereTheGapAllows() {
        // a 4-wide road (z -1..2) with walls at z -2 and 3; pieces jut out of the north wall at x 6 and 12
        world.walls(-5, -2, 30, -2).walls(-5, 3, 30, 3).wall(6, -1).wall(12, -1);

        LocalLegSearch.Result r = find(0.5, -0.5, 20.5, -0.5, OPEN);

        assertTrue(r.found(), r.toString());
        List<NavPoint> line = walked(world.feet(0.5, -0.5), r.waypoints());
        Corridor c = Corridor.build(world, List.of(world.feet(0.5, 0.5), world.feet(20.5, 0.5)), 4);
        assertTrue(leastClearance(c, line, 1.5) >= WALL_CLEARANCE - 1e-6,
            "wall-clearance kept past the jutting pieces: " + line);
        assertTrue(r.waypoints().size() <= 4, "smoothed to a few straight steps: " + r.waypoints());
        assertEquals(20.5, r.waypoints().get(r.waypoints().size() - 1).x(), 1e-9, "ends at the leg's target");
    }

    @Test
    void aLegStillPassesAOneBlockDoor() {
        world.walls(10, -10, 10, 10);
        world.walls.remove(GridWorld.key(10, 3)); // the door

        LocalLegSearch.Result r = find(5.5, 0.5, 15.5, 0.5, OPEN);

        assertTrue(r.found(), r.toString());
        List<NavPoint> line = walked(world.feet(5.5, 0.5), r.waypoints());
        boolean throughDoor = false;
        for (int i = 1; i < line.size(); i++) {
            NavPoint a = line.get(i - 1);
            NavPoint b = line.get(i);
            if (a.x() <= 10.5 && b.x() >= 10.5 && b.x() > a.x()) {
                double t = (10.5 - a.x()) / (b.x() - a.x());
                double z = a.z() + (b.z() - a.z()) * t;
                throughDoor |= z > 3 && z < 4;
            }
        }
        assertTrue(throughDoor, "through the door's cell: " + line);
        Corridor c = Corridor.build(world, line, 4);
        assertTrue(leastClearance(c, line, 0.5) >= LocalLegSearch.BODY_HALF - 1e-6, "the body's room everywhere");
    }

    @Test
    void theSmoothedLegNeverLeavesFloorContinuity() {
        // an L-shaped walkway on a drop: along z 0 to x 10, then along x 10 to z 10
        for (int x = -5; x <= 20; x++) {
            for (int z = -5; z <= 20; z++) {
                boolean walk = (z == 0 && x >= 0 && x <= 10) || (x == 10 && z >= 0 && z <= 10)
                    || (z == 1 && x >= 0 && x <= 10) || (x == 9 && z >= 0 && z <= 10);
                if (!walk) {
                    world.hole(x, z);
                }
            }
        }

        LocalLegSearch.Result r = find(0.5, 0.5, 9.5, 10.5, OPEN);

        assertTrue(r.found(), r.toString());
        List<NavPoint> line = walked(world.feet(0.5, 0.5), r.waypoints());
        for (int i = 1; i < line.size(); i++) {
            assertTrue(NavGeometry.straightWalkable(world, line.get(i - 1), line.get(i)),
                "step " + i + " is a straight walk on floor: " + line);
        }
        assertTrue(line.size() >= 3, "a turn at the corner, not the diagonal over the drop: " + line);
    }

    @Test
    void aStepUpAndDownIsWalkedWithFloorContinuity() {
        world.walls(-5, -2, 20, -2).walls(-5, 2, 20, 2);
        for (int z = -1; z <= 1; z++) {
            world.floorAt(6, z, 65).floorAt(7, z, 65);
        }

        LocalLegSearch.Result r = find(0.5, 0.5, 12.5, 0.5, OPEN);

        assertTrue(r.found(), r.toString());
        List<NavPoint> line = walked(world.feet(0.5, 0.5), r.waypoints());
        for (int i = 1; i < line.size(); i++) {
            assertTrue(NavGeometry.straightWalkable(world, line.get(i - 1), line.get(i)), line.toString());
        }
    }

    @Test
    void aLegIntoARegionTheNpcMayNotEnterIsNotFound() {
        // the keep (x >= 10) is denied by the leg's cell access
        CellAccess denyKeep = (x, feetY, z) -> x >= 10 ? CellAccess.BLOCKED : 0.0;
        LocalLeg leg = new LocalLeg(denyKeep, -100, 0, -100, 100, 255, 100, true);

        LocalLegSearch.Result into = find(0.5, 0.5, 15.5, 0.5, leg);
        assertFalse(into.found());
        assertEquals(LocalLegSearch.Status.NO_PATH, into.status());

        // round a denied patch in the way, never through it
        CellAccess denyPatch = (x, feetY, z) -> x >= 4 && x <= 6 && z >= -3 && z <= 3 ? CellAccess.BLOCKED : 0.0;
        LocalLegSearch.Result round = find(0.5, 0.5, 10.5, 0.5,
            new LocalLeg(denyPatch, -100, 0, -100, 100, 255, 100, true));
        assertTrue(round.found(), round.toString());
        for (NavPoint p : walked(world.feet(0.5, 0.5), round.waypoints())) {
            assertFalse(p.x() >= 4 && p.x() < 7 && p.z() >= -3 && p.z() < 4, "outside the patch: " + p);
        }
    }

    @Test
    void cellsOutsideTheLegsBoxAreNotUsed() {
        world.walls(5, -3, 5, 10); // a wall with a way round only at z -4 and beyond
        LocalLeg box = new LocalLeg(CellAccess.OPEN, -10, 0, -3, 20, 255, 10, true);

        assertFalse(find(0.5, 0.5, 10.5, 0.5, box).found(), "the way round leaves the captured box");
        assertTrue(find(0.5, 0.5, 10.5, 0.5, OPEN).found());
    }

    @Test
    void theCellBudgetBoundsTheSearch() {
        // the target is walled in: the search would look at the whole corridor
        world.walls(14, -2, 18, -2).walls(14, 2, 18, 2).walls(14, -2, 14, 2).walls(18, -2, 18, 2);

        LocalLegSearch.Result small = LocalLegSearch.search(world, world.feet(0.5, 0.5), floor(16.5, 0.5), OPEN,
            WALL_CLEARANCE, 50);
        assertEquals(LocalLegSearch.Status.BUDGET, small.status());
        assertEquals(50, small.expansions());

        LocalLegSearch search = new LocalLegSearch();
        LocalLegSearch.Result full = search.find(world, world.feet(0.5, 0.5), floor(16.5, 0.5), OPEN, WALL_CLEARANCE);
        assertEquals(LocalLegSearch.Status.NO_PATH, full.status(), "walled in: no path, not a guess");
        assertTrue(full.expansions() <= LocalLegSearch.MAX_EXPANSIONS);
        // the corridor of a 16-block leg, 10 either side: at most (16 + 2 x 12) x (2 x 12) columns, a few reads each
        assertTrue(full.reads() < 40 * 24 * 8, "reads bounded: " + full.reads());
        assertEquals(1, search.searches());
        assertEquals(0, search.found());
        assertTrue(search.nanos() > 0);
    }
}

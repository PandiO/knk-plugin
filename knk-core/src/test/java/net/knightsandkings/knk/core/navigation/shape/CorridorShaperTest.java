package net.knightsandkings.knk.core.navigation.shape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/**
 * Corridor steering's shaped line (implementation plan 4c.4 item 2.2, link M2): it keeps {@code wall-clearance} where
 * the road allows and never leaves the corridor or the NPC's floor; string pulling gives the chord on an open square;
 * a doorway gets its three aligned waypoints; a one-block door stays open; the cost is bounded and a second NPC on the
 * same stretch reads nothing.
 */
class CorridorShaperTest {

    private static final CorridorShaper.Options OPTIONS = new CorridorShaper.Options(4, 0.8);

    private final GridWorld world = new GridWorld();
    private final AtomicLong tick = new AtomicLong();
    private final CorridorShaper shaper = new CorridorShaper(tick::get, null);

    /** A line every 1.5 blocks from a to b, as the route follower hands it (feet). */
    private List<NavPoint> line(double ax, double az, double bx, double bz) {
        List<NavPoint> out = new ArrayList<>();
        double len = Math.hypot(bx - ax, bz - az);
        int n = (int) Math.ceil(len / 1.5);
        for (int i = 0; i <= n; i++) {
            double t = (double) i / n;
            out.add(world.feet(ax + (bx - ax) * t, az + (bz - az) * t));
        }
        return out;
    }

    private static List<Integer> indexes(int n) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(i);
        }
        return out;
    }

    private CorridorShaper.ShapedLine shape(List<NavPoint> feet, boolean keepLast) {
        return shaper.shape(world, feet, indexes(feet.size()), OPTIONS, keepLast);
    }

    /** The least clearance along every step of the shaped line, sampled every 0.05 block. */
    private static double leastClearance(CorridorShaper.ShapedLine shaped, Corridor c) {
        double least = Double.MAX_VALUE;
        List<NavPoint> p = shaped.waypoints();
        for (int k = 1; k < p.size(); k++) {
            NavPoint a = p.get(k - 1);
            NavPoint b = p.get(k);
            int samples = (int) Math.ceil(a.horizontalDistance(b) / 0.05);
            for (int i = 0; i <= samples; i++) {
                double t = samples == 0 ? 0 : (double) i / samples;
                least = Math.min(least, c.clearanceAt(a.x() + (b.x() - a.x()) * t, a.z() + (b.z() - a.z()) * t));
            }
        }
        return least;
    }

    /** Every waypoint is in the corridor, on its cell's floor. */
    private static void onCorridorFloors(CorridorShaper.ShapedLine shaped) {
        for (NavPoint p : shaped.waypoints()) {
            Corridor c = shaped.corridorAt(p);
            assertTrue(c != null, "waypoint " + p + " is in its corridor, on its floor");
        }
    }

    @Test
    void aLineAlongAWallMovesToTheClearance() {
        world.walls(-5, -1, 30, -1).walls(-5, 5, 30, 5); // 5 wide, z 0..4
        List<NavPoint> feet = line(0.5, 0.2, 25.5, 0.2); // 0.2 from the north wall

        CorridorShaper.ShapedLine shaped = shape(feet, false);

        Corridor c = shaped.corridors().get(0);
        assertTrue(leastClearance(shaped, c) >= 0.8 - 0.06, "kept " + leastClearance(shaped, c));
        onCorridorFloors(shaped);
        for (NavPoint p : shaped.waypoints()) {
            assertTrue(p.z() < 1.2, "moved no further than the clearance needs: " + p);
        }
    }

    @Test
    void aJaggedStraightIsWalkedStraightWithTheClearance() {
        // 3 wide (x 14..16), one-block notches in both walls every 2 to 3 blocks, the skeleton line wobbling into them
        world.walls(13, -2, 13, 32).walls(17, -2, 17, 32);
        int[] east = {4, 7, 9, 12, 15, 17, 20, 23, 25, 28};
        int[] west = {3, 5, 8, 11, 13, 16, 19, 21, 24, 27};
        for (int z : east) {
            world.walls.remove(GridWorld.key(17, z));
            world.wall(18, z);
        }
        for (int z : west) {
            world.walls.remove(GridWorld.key(13, z));
            world.wall(12, z);
        }
        List<NavPoint> feet = new ArrayList<>();
        for (int z = 0; z <= 30; z++) {
            double min = contains(west, z) ? 13 : 14;
            double max = contains(east, z) ? 17 : 16;
            feet.add(world.feet((min + max + 1) / 2.0, z + 0.5));
        }

        CorridorShaper.ShapedLine shaped = shape(feet, false);

        Corridor c = shaped.corridors().get(0);
        assertTrue(leastClearance(shaped, c) >= 0.8 - 0.06, "kept " + leastClearance(shaped, c));
        assertTrue(shaped.waypoints().size() <= 4, "straightened: " + shaped.waypoints());
        onCorridorFloors(shaped);
        for (NavPoint p : shaped.waypoints()) {
            assertTrue(p.x() >= 14.8 - 1e-6 && p.x() <= 16.2 + 1e-6, "not pulled into a notch: " + p);
        }
    }

    private static boolean contains(int[] values, int v) {
        for (int x : values) {
            if (x == v) {
                return true;
            }
        }
        return false;
    }

    @Test
    void anOpenSquareGivesTheChord() {
        // the road graph's V through a plaza junction: (0,0) -> (8,3) -> (16,0)
        List<NavPoint> feet = new ArrayList<>(line(0.5, 0.5, 8.5, 3.5));
        List<NavPoint> second = line(8.5, 3.5, 16.5, 0.5);
        feet.addAll(second.subList(1, second.size()));

        CorridorShaper.ShapedLine shaped = shape(feet, false);

        double length = 0;
        List<NavPoint> p = shaped.waypoints();
        for (int k = 1; k < p.size(); k++) {
            length += p.get(k - 1).horizontalDistance(p.get(k));
        }
        assertEquals(16.0, length, 16.0 * 0.02, "the chord's length: " + p);
        for (NavPoint q : p) {
            assertTrue(q.z() < 1.5, "never near the junction (8, 3): " + q);
        }
    }

    @Test
    void aDoorwayGetsThreeWaypointsOnItsAxis() {
        world.walls(-5, 10, 25, 10);
        world.walls.remove(GridWorld.key(12, 10)); // a one-block door in a wall running east-west
        List<NavPoint> feet = new ArrayList<>(line(8.5, 6.5, 12.5, 10.5));
        List<NavPoint> second = line(12.5, 10.5, 16.5, 14.5);
        feet.addAll(second.subList(1, second.size()));

        CorridorShaper.ShapedLine shaped = shape(feet, false);

        assertEquals(1, shaped.chokepoints());
        List<NavPoint> p = shaped.waypoints();
        int door = -1;
        for (int k = 0; k < p.size(); k++) {
            if (Math.abs(p.get(k).x() - 12.5) < 0.11 && Math.abs(p.get(k).z() - 10.5) < 0.11) {
                door = k;
            }
        }
        assertTrue(door > 0 && door < p.size() - 1, "a waypoint in the door's middle: " + p);
        assertEquals(12.5, p.get(door - 1).x(), 0.11, "before it, on the axis: " + p);
        assertEquals(9.0, p.get(door - 1).z(), 0.11);
        assertEquals(12.5, p.get(door + 1).x(), 0.11, "after it, on the axis: " + p);
        assertEquals(12.0, p.get(door + 1).z(), 0.11);
        onCorridorFloors(shaped);
    }

    @Test
    void aOneBlockPassageStaysOpenThroughItsMiddle() {
        world.walls(-5, -1, 30, -1).walls(-5, 1, 30, 1); // 1 wide for its whole length: no chokepoint, a passage
        List<NavPoint> feet = line(0.3, 0.3, 25.3, 0.3);

        CorridorShaper.ShapedLine shaped = shape(feet, false);

        assertEquals(0, shaped.chokepoints());
        onCorridorFloors(shaped);
        for (NavPoint p : shaped.waypoints()) {
            assertEquals(0.5, p.z(), 0.11, "in the middle: " + p);
        }
    }

    @Test
    void aLedgeIsKeptClearOfAndTheLineStaysOnItsFloor() {
        for (int x = -5; x <= 30; x++) {
            world.hole(x, -1); // a drop north of a 4-wide road z 0..3
        }
        world.walls(-5, 4, 30, 4);
        List<NavPoint> feet = line(0.5, 0.1, 25.5, 0.1);

        CorridorShaper.ShapedLine shaped = shape(feet, false);

        assertTrue(leastClearance(shaped, shaped.corridors().get(0)) >= 0.8 - 0.06);
        for (NavPoint p : shaped.waypoints()) {
            assertEquals(65, p.blockY(), "on the road's floor: " + p);
        }
    }

    @Test
    void aFlightOfStairsKeepsFloorContinuity() {
        // 3 wide (x 0..2), rising a block every 2 blocks northwards, walls either side
        for (int z = -6; z <= 26; z++) {
            for (int x = -3; x <= 5; x++) {
                world.floorAt(x, -z, 64 + Math.max(0, Math.min(20, z)) / 2); // landings at both ends
            }
        }
        world.walls(-1, 6, -1, -26).walls(3, 6, 3, -26);
        List<NavPoint> feet = new ArrayList<>();
        for (int z = 0; z <= 20; z += 1) {
            feet.add(world.feet(0.2, -z + 0.5)); // along the west wall
        }

        CorridorShaper.ShapedLine shaped = shape(feet, false);

        onCorridorFloors(shaped);
        List<NavPoint> p = shaped.waypoints();
        for (int k = 1; k < p.size(); k++) {
            assertTrue(Math.abs(p.get(k).blockY() - p.get(k - 1).blockY()) <= 1, "a step at most: " + p);
        }
        for (NavPoint q : p) {
            assertTrue(q.x() >= 0.8 - 0.06, "off the west wall: " + q);
        }
    }

    @Test
    void theRoutesEndStaysWhereItIs() {
        world.walls(-5, -1, 30, -1).walls(-5, 5, 30, 5);
        List<NavPoint> feet = line(0.5, 0.2, 25.5, 0.2);

        CorridorShaper.ShapedLine shaped = shape(feet, true);

        assertEquals(feet.get(feet.size() - 1), shaped.waypoints().get(shaped.waypoints().size() - 1));
    }

    @Test
    void aLineOverItselfOnAnotherLevelGetsACorridorPerPiece() {
        // the line walks east at floor 64, then comes back west above itself at floor 70
        for (int x = 0; x <= 12; x++) {
            world.floorAt(x, 0, 64);
        }
        List<NavPoint> feet = new ArrayList<>(line(0.5, 0.5, 10.5, 0.5));
        int firstPiece = feet.size();
        feet.add(new NavPoint(GridWorld.W, 10.5, 71, 0.5));
        feet.add(new NavPoint(GridWorld.W, 5.5, 71, 0.5));
        world.floors.clear(); // floor 64 everywhere still; the upper points are taken as given (seeds)

        CorridorShaper.ShapedLine shaped = shaper.shape(world, feet, indexes(feet.size()), OPTIONS, false);

        assertEquals(2, shaped.corridors().size(), "a piece per level, the second from point " + firstPiece);
    }

    @Test
    void theCostIsBoundedAndASecondNpcOnTheSameStretchReadsNothing() {
        world.walls(-5, -2, 40, -2).walls(-5, 2, 40, 2);
        List<NavPoint> feet = line(0.5, 0.5, 32.5, 0.5);

        world.reads = 0;
        CorridorShaper.ShapedLine first = shape(feet, false);
        long firstReads = world.reads;
        // the strip: 33 blocks long, 9 wide, plus the ends; at most isLoaded, 3 floors, 3 headrooms and a region each
        assertTrue(firstReads <= 42 * 9 * 8, "reads " + firstReads);
        assertEquals(firstReads, first.corridors().get(0).reads());

        world.reads = 0;
        CorridorShaper.ShapedLine second = shape(feet, false);
        assertEquals(0, world.reads, "shared: nothing read");
        assertSame(first, second);
        assertEquals(1, shaper.shaped());
        assertEquals(1, shaper.shared());

        tick.addAndGet(CorridorShaper.TTL_TICKS);
        shape(feet, false);
        assertEquals(2, shaper.shaped(), "built again after the reuse time");
        assertNotEquals(0, world.reads);
    }
}

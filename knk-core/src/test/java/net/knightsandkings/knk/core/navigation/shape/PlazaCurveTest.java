package net.knightsandkings.knk.core.navigation.shape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The players' plaza line (off-road navigation phase 7, DESIGN § 8, A22 and A23; PandiO/knk-workspace#712), on the NPC
 * scenarios' plaza (15 × 15, x and z 8..22, walled, 3-wide roads in the middle of its sides, junction 15.5 15.5) and
 * its variants of § 8.8: an L (two roads at a right angle), a T (three roads; the stem to the right arm), a stall on the
 * curve, a region the route does not pass, and a street T junction that gets no line (O19).
 */
class PlazaCurveTest {

    private static final double NODE_X = 15.5;
    private static final double NODE_Z = 15.5;
    private static final PlazaCurve.Options DEFAULTS = PlazaCurve.Options.defaults(4, 0.8);

    private final GridWorld world = new GridWorld();

    /** The walled plaza with road openings on the named sides ('w', 'n', 'e', 's') and their 3-wide roads. */
    private void plaza(String sides) {
        world.walls(7, 7, 23, 7).walls(7, 23, 23, 23).walls(7, 7, 7, 23).walls(23, 7, 23, 23);
        for (int a = 14; a <= 16; a++) {
            if (sides.contains("n")) {
                world.walls.remove(GridWorld.key(a, 7));
            }
            if (sides.contains("s")) {
                world.walls.remove(GridWorld.key(a, 23));
            }
            if (sides.contains("w")) {
                world.walls.remove(GridWorld.key(7, a));
            }
            if (sides.contains("e")) {
                world.walls.remove(GridWorld.key(23, a));
            }
        }
        if (sides.contains("n")) {
            world.walls(13, -6, 13, 6).walls(17, -6, 17, 6);
        }
        if (sides.contains("w")) {
            world.walls(-6, 13, 6, 13).walls(-6, 17, 6, 17);
        }
        if (sides.contains("s")) {
            world.walls(13, 24, 13, 36).walls(17, 24, 17, 36);
        }
        if (sides.contains("e")) {
            world.walls(24, 13, 36, 13).walls(24, 17, 36, 17);
        }
    }

    /** The road graph's V through the node: from {@code (ax, az)} to the node, then to {@code (bx, bz)}. */
    private List<NavPoint> vLine(double ax, double az, double bx, double bz) {
        List<NavPoint> out = new ArrayList<>(line(ax, az, NODE_X, NODE_Z));
        List<NavPoint> out2 = line(NODE_X, NODE_Z, bx, bz);
        out.addAll(out2.subList(1, out2.size()));
        return out;
    }

    private List<NavPoint> westToNorth() {
        return vLine(-4.5, 15.5, 15.5, -4.5);
    }

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

    private static PlazaCrossing junction(int radius) {
        return new PlazaCrossing(new NavPoint(GridWorld.W, NODE_X, 64, NODE_Z), radius);
    }

    private static double midpointDistanceToNode(PlazaCurve.Result r) {
        List<PlazaLine.Point> c = r.curve();
        PlazaLine.Point mid = c.get((c.size() - 1) / 2);
        if ((c.size() - 1) % 2 != 0) {
            PlazaLine.Point other = c.get(c.size() / 2);
            mid = new PlazaLine.Point((mid.x() + other.x()) / 2, (mid.z() + other.z()) / 2, mid.floor());
        }
        return Math.hypot(mid.x() - NODE_X, mid.z() - NODE_Z);
    }

    private static double chordMidToNode(PlazaCurve.Result r) {
        PlazaLine.Point a = r.curve().get(0);
        PlazaLine.Point b = r.curve().get(r.curve().size() - 1);
        return Math.hypot((a.x() + b.x()) / 2 - NODE_X, (a.z() + b.z()) / 2 - NODE_Z);
    }

    /** The line's samples every 0.05 block. */
    private static List<double[]> samples(List<PlazaLine.Point> p) {
        List<double[]> out = new ArrayList<>();
        for (int k = 1; k < p.size(); k++) {
            PlazaLine.Point a = p.get(k - 1);
            PlazaLine.Point b = p.get(k);
            int n = Math.max(1, (int) Math.ceil(Math.hypot(b.x() - a.x(), b.z() - a.z()) / 0.05));
            for (int i = 0; i <= n; i++) {
                double t = (double) i / n;
                out.add(new double[] {a.x() + (b.x() - a.x()) * t, a.z() + (b.z() - a.z()) * t});
            }
        }
        return out;
    }

    /** The least distance from the line's samples to the square of any of the given columns. */
    private static double leastDistanceTo(List<PlazaLine.Point> line, int x1, int z1, int x2, int z2) {
        double least = Double.MAX_VALUE;
        for (double[] s : samples(line)) {
            double dx = Math.max(Math.max(x1 - s[0], 0), s[0] - (x2 + 1));
            double dz = Math.max(Math.max(z1 - s[1], 0), s[1] - (z2 + 1));
            least = Math.min(least, Math.hypot(dx, dz));
        }
        return least;
    }

    // ==================== A22: the pulled curve ====================

    @Test
    void anLPlazaGetsTheRoundedCurvePulledTowardsTheNode() {
        plaza("wn");
        PlazaCurve.Result r = PlazaCurve.cross(world, westToNorth(), junction(7), DEFAULTS);

        assertEquals(PlazaCurve.Kind.CURVE, r.kind(), "failure: " + r.curveFailure());
        assertTrue(r.found());
        assertEquals(7, r.radius(), 1e-9);
        // the entry is on the west road where it meets the footprint, the exit on the north road
        assertEquals(15.5 - 7, r.line().get(0).x(), 1e-6);
        assertEquals(15.5, r.line().get(0).z(), 1e-6);
        PlazaLine.Point exit = r.line().get(r.line().size() - 1);
        assertEquals(15.5, exit.x(), 1e-6);
        assertEquals(15.5 - 7, exit.z(), 1e-6);
        // the curve's midpoint is pulled 35 % of the way from the chord's midpoint to the node
        assertEquals((1 - PlazaCurve.DEFAULT_PULL) * chordMidToNode(r), midpointDistanceToNode(r), 0.05);
        // shorter than the right angle, longer than the chord
        assertEquals(14, r.routeLength(), 1e-6);
        assertEquals(7 * Math.sqrt(2), r.chordLength(), 1e-6);
        assertTrue(r.lineLength() < r.routeLength() - 1, "shorter than the V: " + r.lineLength());
        assertTrue(r.lineLength() > r.chordLength(), "rounded, not the chord: " + r.lineLength());
        // keeps the wall clearance and stays inside the footprint
        assertTrue(r.minClearance() >= 0.8 - 0.05, "clearance " + r.minClearance());
        for (double[] s : samples(r.line())) {
            assertTrue(Math.hypot(s[0] - NODE_X, s[1] - NODE_Z) <= 7 + 1e-6, "inside the footprint: " + s[0] + " " + s[1]);
        }
        // where it sits on the route polyline: 13 blocks of the west road before the footprint, 14 inside it
        assertEquals(13, r.entryAlong(), 1e-6);
        assertEquals(27, r.exitAlong(), 1e-6);
    }

    @Test
    void thePullRunsFromTheChordToTheNode() {
        plaza("wn");
        PlazaCurve.Result chord = PlazaCurve.cross(world, westToNorth(), junction(7), DEFAULTS.withPull(0));
        PlazaCurve.Result node = PlazaCurve.cross(world, westToNorth(), junction(7), DEFAULTS.withPull(1));
        PlazaCurve.Result half = PlazaCurve.cross(world, westToNorth(), junction(7), DEFAULTS.withPull(0.5));

        assertEquals(chordMidToNode(chord), midpointDistanceToNode(chord), 0.05, "pull 0: through the chord's middle");
        assertEquals(0, midpointDistanceToNode(node), 0.05, "pull 1: through the node");
        assertEquals(chordMidToNode(half) / 2, midpointDistanceToNode(half), 0.05);
        assertTrue(chord.curve().size() > 2 && node.curve().size() > 2);
        assertTrue(PlazaCurve.length(chord.curve()) < PlazaCurve.length(half.curve()));
    }

    @Test
    void aTPlazaRoundsTheStemToTheRightArmTowardsTheNode() {
        plaza("wes"); // west and east arms, the stem from the south
        List<NavPoint> route = vLine(15.5, 34.5, 34.5, 15.5); // up the stem, out to the east arm
        PlazaCurve.Result r = PlazaCurve.cross(world, route, junction(0), DEFAULTS);

        assertEquals(PlazaCurve.Kind.CURVE, r.kind(), "failure: " + r.curveFailure());
        // the measured footprint: the open floor round the junction less the wall clearance
        assertTrue(r.radius() > 5 && r.radius() < 7.5, "radius " + r.radius());
        assertEquals((1 - PlazaCurve.DEFAULT_PULL) * chordMidToNode(r), midpointDistanceToNode(r), 0.05);
        assertTrue(r.lineLength() < r.routeLength());
        assertTrue(r.minClearance() >= 0.8 - 0.05, "clearance " + r.minClearance());
        // it bends towards the node: nearer the node than the corner of the square (22, 22) by far
        assertTrue(midpointDistanceToNode(r) < chordMidToNode(r));
    }

    @Test
    void theStraightOnLineOfATIsTheRoadItself() {
        plaza("wes");
        List<NavPoint> route = vLine(-4.5, 15.5, 34.5, 15.5); // west arm to east arm
        PlazaCurve.Result r = PlazaCurve.cross(world, route, junction(0), DEFAULTS);

        assertEquals(PlazaCurve.Kind.CURVE, r.kind(), "failure: " + r.curveFailure());
        assertEquals(r.routeLength(), r.lineLength(), 1e-6);
        assertEquals(r.chordLength(), r.lineLength(), 1e-6);
    }

    // ==================== A23: an obstacle on the curve ====================

    @Test
    void aStallOnTheCurveGivesTheGuidedLineRoundIt() {
        plaza("wn");
        PlazaCurve.Result free = PlazaCurve.cross(world, westToNorth(), junction(7), DEFAULTS);
        PlazaLine.Point mid = free.curve().get(free.curve().size() / 2);
        int sx = (int) Math.floor(mid.x());
        int sz = (int) Math.floor(mid.z());
        world.walls(sx - 1, sz - 1, sx, sz); // a 2 × 2 stall right on the curve

        PlazaCurve.Result r = PlazaCurve.cross(world, westToNorth(), junction(7), DEFAULTS);

        assertEquals(PlazaCurve.Kind.GUIDED, r.kind());
        assertNotNull(r.curveFailure());
        assertTrue(r.curveFailure().contains("obstacle") || r.curveFailure().contains("floor"), r.curveFailure());
        assertTrue(r.expanded() > 0 && r.expanded() <= PlazaLine.CELL_BUDGET);
        assertTrue(leastDistanceTo(r.line(), sx - 1, sz - 1, sx, sz) >= CorridorShaper.HARD_MINIMUM - 1e-6,
            "never through the stall: " + r.line());
        assertTrue(r.minClearance() >= 0.8 - 0.1, "kept the clearance: " + r.minClearance());
        assertTrue(r.lineLength() < r.routeLength(), "still shorter than the V: " + r.lineLength());
        for (double[] s : samples(r.line())) {
            assertTrue(Math.hypot(s[0] - NODE_X, s[1] - NODE_Z) <= 8 + 1e-6, "on the plaza: " + s[0] + " " + s[1]);
        }
    }

    @Test
    void theNpcScenesWellOnTheChordIsPassedWithTheClearance() {
        plaza("wnes");
        world.walls(11, 11, 12, 12); // the 2 × 2 well of the NPC scenario, on the west to north chord
        PlazaCurve.Result r = PlazaCurve.cross(world, westToNorth(), junction(0), DEFAULTS);

        assertTrue(r.found(), r.kind() + ": " + r.curveFailure());
        assertTrue(leastDistanceTo(r.line(), 11, 11, 12, 12) >= 0.8 - 0.1, "round the well: " + r.line());
        assertTrue(r.lineLength() < r.routeLength(), "shorter than the V: " + r.lineLength());
    }

    @Test
    void whenNeitherFindsALineTheRouteKeepsItsOwnPolyline() {
        plaza("wn");
        // a ditch across the plaza where the route leaves a 5-block footprint: the exit has no floor (the route's own
        // points lie on either side of it, so the corridor has no cell there), and no line reaches it
        for (int x = 8; x <= 22; x++) {
            world.hole(x, 10);
        }
        PlazaCurve.Result r = PlazaCurve.cross(world, westToNorth(), junction(5), DEFAULTS);

        assertEquals(PlazaCurve.Kind.NONE, r.kind());
        assertFalse(r.found());
        assertTrue(r.line().isEmpty());
        assertNotNull(r.curveFailure());
        assertFalse(r.curve().isEmpty(), "the rejected curve is kept for the preview");
    }

    // ==================== regions, street junctions, the route ====================

    @Test
    void aRegionTheRouteDoesNotPassIsKeptOutOf() {
        plaza("wn");
        PlazaCurve.Result free = PlazaCurve.cross(world, westToNorth(), junction(7), DEFAULTS);
        PlazaLine.Point mid = free.curve().get(free.curve().size() / 2);
        // a private garden round the curve's middle
        int gx = (int) Math.floor(mid.x());
        int gz = (int) Math.floor(mid.z());
        for (int x = gx - 1; x <= gx + 1; x++) {
            for (int z = gz - 1; z <= gz + 1; z++) {
                world.regions.put(GridWorld.key(x, z), Set.of("garden"));
            }
        }
        PlazaCurve.Result r = PlazaCurve.cross(world, westToNorth(), junction(7), DEFAULTS);

        assertEquals(PlazaCurve.Kind.GUIDED, r.kind());
        for (double[] s : samples(r.line())) {
            int x = (int) Math.floor(s[0]);
            int z = (int) Math.floor(s[1]);
            assertFalse(x >= gx - 1 && x <= gx + 1 && z >= gz - 1 && z <= gz + 1, "in the garden at " + s[0] + " " + s[1]);
        }
    }

    @Test
    void aRegionTheRoutePassesBetweenItsSparsePointsIsPassed() {
        plaza("wn");
        // a gate region across the whole west side of the plaza; the route's own points (a road graph's: the far end,
        // the node, the far end) all lie outside it, but the route passes through it
        for (int x = 8; x <= 10; x++) {
            for (int z = 8; z <= 22; z++) {
                world.regions.put(GridWorld.key(x, z), Set.of("west_gate"));
            }
        }
        List<NavPoint> sparse = List.of(world.feet(-4.5, 15.5), world.feet(15.5, 15.5), world.feet(15.5, -4.5));
        PlazaCurve.Result r = PlazaCurve.cross(world, sparse, junction(7), DEFAULTS);

        assertEquals(PlazaCurve.Kind.CURVE, r.kind(), "failure: " + r.curveFailure());
    }

    @Test
    void aStreetTJunctionGetsNoPlazaLine() {
        // three 3-wide streets meeting at (15.5, 15.5): no open floor wider than the corridor half width
        world.walls(-6, 13, 13, 13).walls(17, 13, 36, 13).walls(-6, 17, 36, 17);
        world.walls(13, -6, 13, 13).walls(17, -6, 17, 13);
        for (int a = 14; a <= 16; a++) {
            world.walls.remove(GridWorld.key(a, 13));
        }
        PlazaCurve.Result r = PlazaCurve.cross(world, westToNorth(), junction(0), DEFAULTS);

        assertEquals(PlazaCurve.Kind.STREET, r.kind());
        assertFalse(r.found());
        assertTrue(r.line().isEmpty());
        assertTrue(r.curve().isEmpty());
    }

    @Test
    void aDesignedPlazaIsAPlazaWhateverItsOpenFloor() {
        plaza("wn");
        PlazaCurve.Result r = PlazaCurve.cross(world, westToNorth(), junction(5), DEFAULTS);

        assertEquals(PlazaCurve.Kind.CURVE, r.kind());
        assertEquals(5, r.radius(), 1e-9);
    }

    @Test
    void theDesignedRadiusIsCappedAtPlazaCorridorBlocks() {
        plaza("wn");
        PlazaCurve.Options small = new PlazaCurve.Options(4, 0.8, 6, PlazaCurve.DEFAULT_PULL, 2);
        PlazaCurve.Result r = PlazaCurve.cross(world, westToNorth(), junction(12), small);

        assertEquals(6, r.radius(), 1e-9);
    }

    @Test
    void aRouteThatEndsOnThePlazaOrMissesTheNodeCrossesNothing() {
        plaza("wn");
        List<NavPoint> ending = line(-4.5, 15.5, 15.5, 15.5); // ends at the node
        assertEquals(PlazaCurve.Kind.NOT_CROSSED, PlazaCurve.cross(world, ending, junction(7), DEFAULTS).kind());

        List<NavPoint> past = line(-4.5, 40.5, 30.5, 40.5);
        assertEquals(PlazaCurve.Kind.NOT_CROSSED, PlazaCurve.cross(world, past, junction(7), DEFAULTS).kind());

        List<NavPoint> otherWorld = List.of(new NavPoint("x", 0, 65, 0), new NavPoint("x", 1, 65, 0));
        assertEquals(PlazaCurve.Kind.NOT_CROSSED, PlazaCurve.cross(world, otherWorld, junction(7), DEFAULTS).kind());
    }

    @Test
    void optionsAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> new PlazaCurve.Options(0.5, 0.8, 16, 0.35, 2));
        assertThrows(IllegalArgumentException.class, () -> new PlazaCurve.Options(4, 0.2, 16, 0.35, 2));
        assertThrows(IllegalArgumentException.class, () -> new PlazaCurve.Options(4, 0.8, 0, 0.35, 2));
        assertThrows(IllegalArgumentException.class, () -> new PlazaCurve.Options(4, 0.8, 16, 1.5, 2));
        assertThrows(IllegalArgumentException.class, () -> new PlazaCurve.Options(4, 0.8, 16, 0.35, -1));
        assertEquals(Corridor.CAP, new PlazaCurve.Options(4, 3, 16, 0.35, 2).clearance(), 1e-9);
    }

    // ==================== the curve's geometry ====================

    @Test
    void theControlPointsPutTheMidpointWhereThePullSays() {
        // a right angle: P0 (0, 0) heading +x, P1 (5, -5) heading -z, node (5, 0)
        for (double pull : new double[] {0, 0.35, 0.7, 1}) {
            double[][] c = PlazaCurve.controls(0, 0, 1, 0, 5, -5, 0, -1, 5, 0, pull);
            double[] mid = PlazaCurve.bezier(c, 0.5);
            double mx = 2.5 + pull * (5 - 2.5);
            double mz = -2.5 + pull * (0 + 2.5);
            assertEquals(mx, mid[0], 1e-9);
            assertEquals(mz, mid[1], 1e-9);
            assertEquals(0, PlazaCurve.bezier(c, 0)[0], 1e-12);
            assertEquals(-5, PlazaCurve.bezier(c, 1)[1], 1e-12);
        }
    }

    // ==================== the guided search ====================

    @Test
    void theGuidedSearchWithoutAGuideIsThePlainSearch() {
        plaza("wn");
        world.walls(11, 11, 12, 12); // the well on the chord
        List<NavPoint> v = westToNorth();
        Corridor corridor = Corridor.build(world, v, 4, false, List.of(new Corridor.Disc(15.5, 15.5, 8)));
        PlazaLine.Point a = new PlazaLine.Point(8.5, 15.5, 64);
        PlazaLine.Point b = new PlazaLine.Point(15.5, 8.5, 64);
        Corridor.Disc area = new Corridor.Disc(15.5, 15.5, 8);

        PlazaLine.Result plain = PlazaLine.search(corridor, a, b, area, 0.8);
        assertEquals(plain.line(), PlazaLine.search(corridor, a, b, area, 0.8, null, 2).line());
        assertEquals(plain.line(), PlazaLine.search(corridor, a, b, area, 0.8, List.of(), 2).line());
        assertEquals(plain.line(), PlazaLine.search(corridor, a, b, area, 0.8, List.of(a, b), 0).line());
        assertEquals(plain.expanded(), PlazaLine.search(corridor, a, b, area, 0.8, null, 2).expanded());
        assertThrows(IllegalArgumentException.class, () -> PlazaLine.search(corridor, a, b, area, 0.8, null, -1));
    }

    @Test
    void theGuideChoosesTheSideOfAnObstacle() {
        plaza("we");
        world.walls(14, 14, 16, 16); // a 3 × 3 fountain on the node
        List<NavPoint> v = vLine(-4.5, 15.5, 34.5, 15.5);
        Corridor.Disc area = new Corridor.Disc(15.5, 15.5, 8);
        Corridor corridor = Corridor.build(world, v, 4, false, List.of(area));
        PlazaLine.Point a = new PlazaLine.Point(8.5, 15.5, 64);
        PlazaLine.Point b = new PlazaLine.Point(22.5, 15.5, 64);

        List<PlazaLine.Point> north = List.of(a, new PlazaLine.Point(15.5, 11.5, 64), b);
        List<PlazaLine.Point> south = List.of(a, new PlazaLine.Point(15.5, 19.5, 64), b);
        PlazaLine.Result viaNorth = PlazaLine.search(corridor, a, b, area, 0.8, north, 2);
        PlazaLine.Result viaSouth = PlazaLine.search(corridor, a, b, area, 0.8, south, 2);

        assertTrue(viaNorth.found() && viaSouth.found());
        assertTrue(viaNorth.line().stream().anyMatch(p -> p.z() < 14), "north of the fountain: " + viaNorth.line());
        assertTrue(viaSouth.line().stream().anyMatch(p -> p.z() > 17), "south of the fountain: " + viaSouth.line());
    }

    @Test
    void theCheckNamesWhatFailed() {
        plaza("wn");
        world.walls(11, 11, 12, 12);
        List<NavPoint> v = westToNorth();
        Corridor.Disc disc = new Corridor.Disc(15.5, 15.5, 8);
        Corridor corridor = Corridor.build(world, v, 4, false, List.of(disc));
        List<PlazaLine.Point> through = new ArrayList<>();
        for (int i = 0; i <= 20; i++) {
            double t = i / 20.0;
            through.add(new PlazaLine.Point(8.5 + 7 * t, 15.5 - 7 * t, 64));
        }
        String why = PlazaCurve.check(corridor, disc, through, 0.8);
        assertNotNull(why);

        List<PlazaLine.Point> clear = List.of(new PlazaLine.Point(8.5, 15.5, 64), new PlazaLine.Point(12, 15.5, 64),
            new PlazaLine.Point(15.5, 15.5, 64));
        assertNull(PlazaCurve.check(corridor, disc, clear, 0.8));
    }
}

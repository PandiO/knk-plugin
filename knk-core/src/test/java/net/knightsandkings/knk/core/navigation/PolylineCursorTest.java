package net.knightsandkings.knk.core.navigation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The cursor that follows a player both ways with continuity (off-road DESIGN § 7.2.3, § 7.5.2, § 7.5.6; A20). */
class PolylineCursorTest {

    private static final double INF = Double.POSITIVE_INFINITY;

    private static List<double[]> line(double[]... points) {
        return List.of(points);
    }

    /** A straight road along x at floor 64, one point per block (like a road polyline). */
    private static List<double[]> straight(int length) {
        List<double[]> out = new ArrayList<>();
        for (int x = 0; x <= length; x++) {
            out.add(new double[] {x, 64, 0});
        }
        return out;
    }

    /**
     * A switchback: east along z = 0 to x = 40, a 4-block turn, west along z = 4 back to x = 0. The arms are 4 blocks
     * apart; along the route a point of the first arm and the one beside it differ by 2 * (40 - x) + 4.
     */
    private static List<double[]> switchback() {
        return line(new double[] {0, 64, 0}, new double[] {40, 64, 0}, new double[] {40, 64, 4}, new double[] {0, 64, 4});
    }

    /** A loop: a square of 20 east, north, west, south back to the start, then on east past it to x = 40. */
    private static List<double[]> loop() {
        return line(new double[] {0, 64, 0}, new double[] {20, 64, 0}, new double[] {20, 64, 20}, new double[] {0, 64, 20},
            new double[] {0, 64, 0.5}, new double[] {40, 64, 0.5});
    }

    private static PolylineCursor.Step step(List<double[]> line, double previous, double x, double z, double window,
                                            double leave, double margin) {
        return PolylineCursor.follow(line, PolylineCursor.cumulative(line), previous, x, 64, z, window, leave, margin);
    }

    @Test
    void cumulativeAndPointAt() {
        List<double[]> l = line(new double[] {0, 0, 0}, new double[] {3, 4, 0}, new double[] {3, 4, 10});
        double[] c = PolylineCursor.cumulative(l);
        assertEquals(0, c[0], 1e-9);
        assertEquals(5, c[1], 1e-9);
        assertEquals(15, c[2], 1e-9);
        double[] p = PolylineCursor.pointAt(l, c, 10);
        assertEquals(3, p[0], 1e-9);
        assertEquals(5, p[2], 1e-9);
        assertEquals(10, PolylineCursor.pointAt(l, c, 99)[2], 1e-9, "clamped to the end");
        assertEquals(0, PolylineCursor.pointAt(l, c, -3)[0], 1e-9, "clamped to the start");
    }

    @Test
    void firstStepTakesTheNearestPoint() {
        PolylineCursor.Step s = step(straight(50), Double.NaN, 30, 2, 12, 8, INF);
        assertTrue(s.onPolyline());
        assertEquals(30, s.along(), 1e-9);
        assertEquals(2, s.distance(), 1e-9);
    }

    @Test
    void followsThePlayerForwardsAndBackwards() {
        List<double[]> road = straight(100);
        double cursor = Double.NaN;
        for (int x = 0; x <= 60; x++) {
            cursor = step(road, cursor, x + 0.3, 1, 12, 8, INF).along();
        }
        assertEquals(60.3, cursor, 1e-9);
        for (int x = 60; x >= 40; x--) {
            cursor = step(road, cursor, x + 0.3, -1, 12, 8, INF).along();
        }
        assertEquals(40.3, cursor, 1e-9, "the cursor winds back with the player");
    }

    @Test
    void aCornerDoesNotHoldTheCursorBack() {
        // a right-angled corner at (20, 0): a player past it and off the line by 1 must not stay on the corner
        List<double[]> corner = line(new double[] {0, 64, 0}, new double[] {20, 64, 0}, new double[] {20, 64, 20});
        double cursor = 20.1;
        for (double z = 0.6; z <= 6; z += 0.5) {
            cursor = step(corner, cursor, 21, z, 12, 8, INF).along();
        }
        assertEquals(25.6, cursor, 1e-6);
    }

    @Test
    void offThePolylineTheCursorStays() {
        PolylineCursor.Step s = step(straight(100), 50, 50, 20, 12, 8, INF);
        assertFalse(s.onPolyline());
        assertEquals(50, s.along(), 1e-9);
        assertEquals(20, s.distance(), 1e-9, "the distance to the nearest point");
    }

    @Test
    void aSwitchbackDoesNotJumpToTheOtherArm() {
        List<double[]> route = switchback();
        // walking east on the first arm, 1.5 blocks off it towards the other arm (2.5 from it)
        double cursor = Double.NaN;
        for (int x = 0; x <= 36; x++) {
            cursor = step(route, cursor, x, 1.5, 12, 8, INF).along();
        }
        assertEquals(36, cursor, 1e-9);
        // standing exactly between the arms near the turn (both 2 away, 12 apart along the route): continuity holds
        PolylineCursor.Step between = step(route, cursor, 38, 2, 12, 8, INF);
        assertEquals(38, between.along(), 1e-9, "the first arm, nearer the previous cursor");
        assertFalse(between.jumped());
        // a player really on the other arm: the cursor follows them round
        double c = between.along();
        for (int z = 1; z <= 4; z++) {
            c = step(route, c, 40, z, 12, 8, INF).along();
        }
        for (int x = 39; x >= 30; x--) {
            c = step(route, c, x, 4, 12, 8, INF).along();
        }
        assertEquals(54, c, 1e-9, "40 east, 4 round the turn, 10 back west");
    }

    @Test
    void aLoopKeepsTheCursorOnTheRightBranch() {
        List<double[]> route = loop();
        double cursor = 0; // the session starts its cursor at the route's start
        // the first pass east along z = 0: near the start the closing side of the loop (z = 0.5, 79.5 blocks along) is
        // nearer than nothing - and both are within reroute-distance, but the far one is more than cursor-jump away
        for (int x = 1; x <= 10; x++) {
            PolylineCursor.Step s = step(route, cursor, x, 0.4, 12, 8, INF);
            cursor = s.along();
            assertFalse(s.jumped(), "x = " + x);
        }
        assertEquals(10, cursor, 1e-9, "still on the first pass");
        // round the loop: back at the start on the second pass the cursor is past 80
        for (int z = 1; z <= 19; z++) {
            cursor = step(route, cursor, 20, z, 12, 8, INF).along();
        }
        for (int x = 19; x >= 1; x--) {
            cursor = step(route, cursor, x, 20, 12, 8, INF).along();
        }
        for (int z = 19; z >= 1; z--) {
            cursor = step(route, cursor, 0, z, 12, 8, INF).along();
        }
        for (int x = 1; x <= 10; x++) {
            cursor = step(route, cursor, x, 0.6, 12, 8, INF).along();
        }
        assertEquals(79.5 + 10, cursor, 0.2, "the second pass, though the first pass is as near");
    }

    @Test
    void aFarCandidateIsTakenWhenThePreviousOneIsBeyondRerouteDistance() {
        List<double[]> route = switchback();
        // cursor at 5 on the first arm; the player stands on the second arm at x = 5 (4 away from the first arm,
        // 75 along): within reroute-distance of both - the first arm holds
        PolylineCursor.Step held = step(route, 5, 5, 4, 12, 8, INF);
        assertEquals(5, held.along(), 1e-9);
        // with a reroute-distance of 3 the first arm's candidate is beyond it: the far candidate is taken
        PolylineCursor.Step jumped = step(route, 5, 5, 4, 12, 3, INF);
        assertTrue(jumped.jumped());
        assertEquals(79, jumped.along(), 1e-9);
    }

    @Test
    void aWalkPathCursorJumpsOnlyWhenThePlayerIsReallyNearer() {
        // a spiral-like path: east 10, up 3 and back west 10 above it
        List<double[]> path = line(new double[] {0, 64, 0}, new double[] {10, 64, 0}, new double[] {10, 67, 1},
            new double[] {0, 67, 1});
        double[] c = PolylineCursor.cumulative(path);
        // on the lower run at x = 3; the upper run is 3 blocks up: the cursor stays below
        PolylineCursor.Step below = PolylineCursor.follow(path, c, 2.5, 3, 64.5, 0.4, PolylineCursor.PATH_WINDOW, INF,
            PolylineCursor.PATH_NEARER);
        assertEquals(3, below.along(), 1e-9);
        assertFalse(below.jumped());
        // the player is up on the upper run at x = 3 (the lower run is 3.2 away): really nearer, the cursor jumps
        PolylineCursor.Step above = PolylineCursor.follow(path, c, 3, 3, 67, 1, PolylineCursor.PATH_WINDOW, INF,
            PolylineCursor.PATH_NEARER);
        assertTrue(above.jumped());
        assertEquals(10 + Math.sqrt(10) + 7, above.along(), 1e-9);
    }

    @Test
    void aSinglePointPolyline() {
        List<double[]> one = line(new double[] {5, 64, 5});
        PolylineCursor.Step s = step(one, Double.NaN, 5, 7, 12, 8, INF);
        assertEquals(0, s.along(), 1e-9);
        assertEquals(2, s.distance(), 1e-9);
        assertEquals(0, step(one, 0, 6, 5, 12, 8, INF).along(), 1e-9);
    }
}

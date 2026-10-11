package net.knightsandkings.knk.core.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for Polygon2D. The contains() cases mirror GateFrameCalculatorRegionModeTest (the
 * extraction must keep the gate tests green); the closest-point/distance cases are new.
 */
class Polygon2DTest {
    private static final double TOLERANCE = 1e-9;

    // === contains ===

    @Test
    void contains_PointInsideSquare_ReturnsTrue() {
        assertTrue(Polygon2D.contains(2, 2, rectangle(0, 0, 4, 4)));
    }

    @Test
    void contains_PointOutsideSquare_ReturnsFalse() {
        assertFalse(Polygon2D.contains(5, 5, rectangle(0, 0, 4, 4)));
        assertFalse(Polygon2D.contains(-1, 2, rectangle(0, 0, 4, 4)));
    }

    @Test
    void contains_PointExactlyOnEdgeOrVertex_ReturnsTrue() {
        List<double[]> square = rectangle(0, 0, 4, 4);
        assertTrue(Polygon2D.contains(0, 2, square));
        assertTrue(Polygon2D.contains(4, 0, square));
        assertTrue(Polygon2D.contains(4, 4, square));
    }

    @Test
    void contains_PointJustOutsideEpsilon_ReturnsFalse() {
        assertFalse(Polygon2D.contains(4.01, 2, rectangle(0, 0, 4, 4)));
        assertTrue(Polygon2D.contains(4.0005, 2, rectangle(0, 0, 4, 4)));
    }

    @Test
    void contains_LShapedConcavePolygon_ExcludesTheNotchButIncludesBothArms() {
        List<double[]> lShape = List.of(
            new double[]{0, 0}, new double[]{6, 0}, new double[]{6, 3},
            new double[]{3, 3}, new double[]{3, 6}, new double[]{0, 6}
        );

        assertTrue(Polygon2D.contains(1, 1, lShape), "bottom-left arm");
        assertTrue(Polygon2D.contains(4, 1, lShape), "bottom-right arm");
        assertTrue(Polygon2D.contains(1, 4, lShape), "top-left arm");
        assertFalse(Polygon2D.contains(4, 4, lShape), "the notched-out quadrant");
    }

    @Test
    void contains_FewerThanThreePoints_ReturnsFalse() {
        assertFalse(Polygon2D.contains(0, 0, List.of(new double[]{0, 0}, new double[]{1, 1})));
        assertFalse(Polygon2D.contains(0, 0, List.of()));
        assertFalse(Polygon2D.contains(0, 0, null));
    }

    // === closestPointOnBoundary ===

    @Test
    void closestPointOnBoundary_PointOutsideFacingAnEdge_IsThePerpendicularFoot() {
        double[] closest = Polygon2D.closestPointOnBoundary(7, 1.5, rectangle(0, 0, 4, 4));
        assertArrayEquals(new double[]{4, 1.5}, closest, TOLERANCE);
    }

    @Test
    void closestPointOnBoundary_PointOutsideBeyondACorner_IsTheCorner() {
        double[] closest = Polygon2D.closestPointOnBoundary(6, 7, rectangle(0, 0, 4, 4));
        assertArrayEquals(new double[]{4, 4}, closest, TOLERANCE);
    }

    @Test
    void closestPointOnBoundary_PointInside_IsOnTheNearestEdge() {
        double[] closest = Polygon2D.closestPointOnBoundary(1, 2.5, rectangle(0, 0, 4, 4));
        assertArrayEquals(new double[]{0, 2.5}, closest, TOLERANCE);
    }

    @Test
    void closestPointOnBoundary_PointOnTheBoundary_IsItself() {
        double[] closest = Polygon2D.closestPointOnBoundary(4, 3, rectangle(0, 0, 4, 4));
        assertArrayEquals(new double[]{4, 3}, closest, TOLERANCE);
    }

    @Test
    void closestPointOnBoundary_ConcaveNotch_UsesTheInnerEdges() {
        List<double[]> lShape = List.of(
            new double[]{0, 0}, new double[]{6, 0}, new double[]{6, 3},
            new double[]{3, 3}, new double[]{3, 6}, new double[]{0, 6}
        );
        // Inside the notch, nearer to the vertical inner edge (u=3) than the horizontal one (v=3).
        double[] closest = Polygon2D.closestPointOnBoundary(3.5, 5, lShape);
        assertArrayEquals(new double[]{3, 5}, closest, TOLERANCE);
    }

    @Test
    void closestPointOnBoundary_DegeneratePolygons() {
        assertNull(Polygon2D.closestPointOnBoundary(1, 1, null));
        assertNull(Polygon2D.closestPointOnBoundary(1, 1, List.of()));
        assertArrayEquals(new double[]{2, 3},
            Polygon2D.closestPointOnBoundary(9, 9, List.of(new double[]{2, 3})), TOLERANCE);
        // Two vertices act as a segment: the foot of (5, 4) on the segment (0,0)-(10,0) is (5, 0).
        assertArrayEquals(new double[]{5, 0},
            Polygon2D.closestPointOnBoundary(5, 4, List.of(new double[]{0, 0}, new double[]{10, 0})), TOLERANCE);
    }

    // === distanceToBoundary ===

    @Test
    void distanceToBoundary_OnTheEdge_IsZero() {
        assertEquals(0.0, Polygon2D.distanceToBoundary(4, 2, rectangle(0, 0, 4, 4)), TOLERANCE);
    }

    @Test
    void distanceToBoundary_Outside_IsThePerpendicularOrCornerDistance() {
        assertEquals(3.0, Polygon2D.distanceToBoundary(7, 2, rectangle(0, 0, 4, 4)), TOLERANCE);
        assertEquals(5.0, Polygon2D.distanceToBoundary(7, 8, rectangle(0, 0, 4, 4)), TOLERANCE);
    }

    @Test
    void distanceToBoundary_Inside_IsTheDistanceToTheNearestEdge() {
        assertEquals(1.0, Polygon2D.distanceToBoundary(1, 2, rectangle(0, 0, 4, 4)), TOLERANCE);
        assertTrue(Polygon2D.contains(1, 2, rectangle(0, 0, 4, 4)));
    }

    @Test
    void distanceToBoundary_EmptyPolygon_IsInfinite() {
        assertEquals(Double.POSITIVE_INFINITY, Polygon2D.distanceToBoundary(0, 0, List.of()));
        assertEquals(Double.POSITIVE_INFINITY, Polygon2D.distanceToBoundary(0, 0, null));
    }

    private static List<double[]> rectangle(double u0, double v0, double u1, double v1) {
        return List.of(
            new double[]{u0, v0}, new double[]{u1, v0}, new double[]{u1, v1}, new double[]{u0, v1}
        );
    }
}

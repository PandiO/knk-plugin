package net.knightsandkings.knk.core.util;

import java.util.List;

/**
 * Pure 2D polygon geometry over vertex lists of {@code double[]{u, v}}, traced in boundary order
 * (not necessarily closed - the last vertex connects back to the first).
 *
 * <p>{@link #contains} is the even-odd ray-casting test that used to live in
 * {@code GateFrameCalculator.pointInPolygon} (which now delegates here): a point on a boundary
 * edge counts as inside, within {@link #EPSILON}, so a block that lands exactly on a captured
 * region's edge (common for a gate's closed frame) isn't dropped by floating-point noise.
 * {@link #closestPointOnBoundary} and {@link #distanceToBoundary} are new, for the router's
 * "guide to the edge of the region" case (DESIGN §6.3).
 *
 * <p>Bukkit-free: shared by the gate frame calculator and the road builder/router.
 */
public final class Polygon2D {
    /** Inclusive-boundary tolerance, in vertex units (blocks, for gate footprints). */
    public static final double EPSILON = 0.001;

    private Polygon2D() {
    }

    /**
     * Even-odd point-in-polygon test with an inclusive boundary.
     *
     * @param polygon the vertices in boundary order; {@code null} or fewer than 3 vertices → false
     */
    public static boolean contains(double u, double v, List<double[]> polygon) {
        if (polygon == null || polygon.size() < 3) {
            return false;
        }

        boolean inside = false;
        int n = polygon.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double ui = polygon.get(i)[0];
            double vi = polygon.get(i)[1];
            double uj = polygon.get(j)[0];
            double vj = polygon.get(j)[1];

            // On-edge check first (inclusive boundary, within epsilon) - a plain ray-cast alone
            // would leave this to floating-point luck.
            if (isOnSegment(u, v, ui, vi, uj, vj)) {
                return true;
            }

            boolean straddles = (vi > v) != (vj > v);
            if (straddles) {
                double uCrossing = ui + (v - vi) / (vj - vi) * (uj - ui);
                if (u < uCrossing) {
                    inside = !inside;
                }
            }
        }
        return inside;
    }

    /**
     * The point on the polygon's boundary nearest to {@code (u, v)} - a perpendicular foot on an
     * edge, or a vertex. Works for a point inside or outside the polygon. A one-vertex "polygon"
     * yields that vertex, a two-vertex one is treated as a segment.
     *
     * @return {@code double[]{u, v}}, or {@code null} for a null/empty polygon
     */
    public static double[] closestPointOnBoundary(double u, double v, List<double[]> polygon) {
        if (polygon == null || polygon.isEmpty()) {
            return null;
        }
        int n = polygon.size();
        if (n == 1) {
            return new double[]{polygon.get(0)[0], polygon.get(0)[1]};
        }

        double[] best = null;
        double bestDistanceSquared = Double.POSITIVE_INFINITY;
        // For two vertices the loop would visit the same segment twice; harmless, same answer.
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double[] candidate = closestPointOnSegment(u, v,
                polygon.get(j)[0], polygon.get(j)[1], polygon.get(i)[0], polygon.get(i)[1]);
            double du = candidate[0] - u;
            double dv = candidate[1] - v;
            double distanceSquared = du * du + dv * dv;
            if (distanceSquared < bestDistanceSquared) {
                bestDistanceSquared = distanceSquared;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Euclidean distance from {@code (u, v)} to the nearest point of the polygon's boundary
     * (0 on the boundary; unsigned, so combine with {@link #contains} when the side matters).
     *
     * @return the distance, or {@code Double.POSITIVE_INFINITY} for a null/empty polygon
     */
    public static double distanceToBoundary(double u, double v, List<double[]> polygon) {
        double[] closest = closestPointOnBoundary(u, v, polygon);
        if (closest == null) {
            return Double.POSITIVE_INFINITY;
        }
        return Math.hypot(closest[0] - u, closest[1] - v);
    }

    private static boolean isOnSegment(double u, double v, double ui, double vi, double uj, double vj) {
        double crossProduct = (v - vi) * (uj - ui) - (u - ui) * (vj - vi);
        if (Math.abs(crossProduct) > EPSILON * Math.max(1.0, Math.hypot(uj - ui, vj - vi))) {
            return false;
        }
        double dotProduct = (u - ui) * (uj - ui) + (v - vi) * (vj - vi);
        if (dotProduct < -EPSILON) {
            return false;
        }
        double squaredLength = (uj - ui) * (uj - ui) + (vj - vi) * (vj - vi);
        return dotProduct <= squaredLength + EPSILON;
    }

    private static double[] closestPointOnSegment(double u, double v, double au, double av, double bu, double bv) {
        double abu = bu - au;
        double abv = bv - av;
        double squaredLength = abu * abu + abv * abv;
        if (squaredLength == 0.0) {
            return new double[]{au, av};
        }
        double t = ((u - au) * abu + (v - av) * abv) / squaredLength;
        t = Math.max(0.0, Math.min(1.0, t));
        return new double[]{au + t * abu, av + t * abv};
    }
}

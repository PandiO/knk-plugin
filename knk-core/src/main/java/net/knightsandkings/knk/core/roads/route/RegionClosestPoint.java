package net.knightsandkings.knk.core.roads.route;

import java.util.ArrayList;
import java.util.List;

/**
 * The goal set for a region destination (DESIGN §6.3, plan 2d {@code RegionShape → goal set}):
 * every point where an edge's polyline crosses into the region (found by bisection on the segment
 * that changes side, so the route ends where the road enters the region - not at the next vertex),
 * or, when no network point lies inside, the single network point closest to the region (sampled
 * every {@value #SAMPLE_SPACING} blocks along every edge). A\* runs multi-goal over the set and
 * stops at the first goal reached. Bukkit-free, pure.
 */
public final class RegionClosestPoint {

    /** Sampling step along edges for the closest-point fallback. */
    public static final double SAMPLE_SPACING = 4;
    private static final int BISECTION_STEPS = 12;

    private RegionClosestPoint() {
    }

    /**
     * @return the crossing points into the region (edge order, then along), or the one closest
     *         network point when nothing is inside; empty only for an empty network
     */
    public static List<SnapPoint> goals(RegionShape shape, RoadNetworkSnapshot snapshot) {
        List<SnapPoint> crossings = crossings(shape, snapshot);
        if (!crossings.isEmpty()) {
            return crossings;
        }
        return closest(shape, snapshot).map(List::of).orElse(List.of());
    }

    /** Every point where a polyline enters the region (from either direction). */
    public static List<SnapPoint> crossings(RegionShape shape, RoadNetworkSnapshot snapshot) {
        List<SnapPoint> goals = new ArrayList<>();
        List<SnapPoint> insideVertices = new ArrayList<>();
        for (int e = 0; e < snapshot.edgeCount(); e++) {
            EdgePolyline p = snapshot.polylineAt(e);
            int edgeId = snapshot.edgeAt(e).id();
            List<int[]> pts = p.points();
            boolean prevInside = inside(shape, pts.get(0));
            if (prevInside) {
                insideVertices.add(SnapPoint.onEdge(snapshot, edgeId, 0));
            }
            for (int i = 1; i < pts.size(); i++) {
                boolean in = inside(shape, pts.get(i));
                if (in != prevInside) {
                    // bisect for the first inside point between pts[i-1] and pts[i]
                    double lo = 0, hi = 1; // lo side = outside end, hi side = inside end
                    int[] outside = prevInside ? pts.get(i) : pts.get(i - 1);
                    int[] insidePt = prevInside ? pts.get(i - 1) : pts.get(i);
                    for (int k = 0; k < BISECTION_STEPS; k++) {
                        double mid = (lo + hi) / 2;
                        double[] q = lerp(outside, insidePt, mid);
                        if (shape.containsFloor(q[0], q[1], q[2])) {
                            hi = mid;
                        } else {
                            lo = mid;
                        }
                    }
                    double fromInsideEnd = hi; // parameter from the outside end
                    double segLen = p.segmentLength(i - 1);
                    double along = prevInside
                        ? p.cumulativeAt(i - 1) + (1 - fromInsideEnd) * segLen
                        : p.cumulativeAt(i - 1) + fromInsideEnd * segLen;
                    goals.add(SnapPoint.onEdge(snapshot, edgeId, along));
                } else if (in) {
                    insideVertices.add(SnapPoint.onEdge(snapshot, edgeId, p.cumulativeAt(i)));
                }
                prevInside = in;
            }
        }
        if (goals.isEmpty()) {
            return insideVertices; // the whole road lies inside: its vertices are the goals
        }
        return goals;
    }

    /** The network point closest to the region (sampled), if the network is not empty. */
    public static java.util.Optional<SnapPoint> closest(RegionShape shape, RoadNetworkSnapshot snapshot) {
        double bestDistance = Double.POSITIVE_INFINITY;
        int bestEdge = -1;
        double bestAlong = 0;
        for (int e = 0; e < snapshot.edgeCount(); e++) {
            EdgePolyline p = snapshot.polylineAt(e);
            double length = p.length();
            for (double along = 0; ; along += SAMPLE_SPACING) {
                double a = Math.min(along, length);
                double[] q = p.pointAt(a);
                double d = shape.distanceFromFloor(q[0], q[1], q[2]);
                if (d < bestDistance) {
                    bestDistance = d;
                    bestEdge = e;
                    bestAlong = a;
                }
                if (a >= length) {
                    break;
                }
            }
        }
        if (bestEdge < 0) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(SnapPoint.onEdge(snapshot, snapshot.edgeAt(bestEdge).id(), bestAlong));
    }

    private static boolean inside(RegionShape shape, int[] p) {
        return shape.containsFloor(p[0], p[1], p[2]);
    }

    private static double[] lerp(int[] a, int[] b, double t) {
        return new double[] {a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
    }
}

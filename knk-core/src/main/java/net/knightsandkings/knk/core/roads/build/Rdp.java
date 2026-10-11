package net.knightsandkings.knk.core.roads.build;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Ramer-Douglas-Peucker in 3D (DESIGN §5.6 step 5, ε = 0.75 by default): keeps the subsequence of
 * an edge's centreline whose every dropped point lies within ε of the simplified polyline. Points
 * are {@code int[]{x, y, z}} and come back as the same arrays, so integer standing positions stay
 * integers. Iterative (a stack), so long chains cannot overflow.
 */
public final class Rdp {
    private Rdp() {
    }

    /** The simplified polyline; the first and last point are always kept. */
    public static List<int[]> simplify(List<int[]> points, double epsilon) {
        Objects.requireNonNull(points, "points");
        if (epsilon < 0) {
            throw new IllegalArgumentException("epsilon must be >= 0");
        }
        int n = points.size();
        if (n <= 2) {
            return new ArrayList<>(points);
        }
        boolean[] keep = new boolean[n];
        keep[0] = true;
        keep[n - 1] = true;
        ArrayDeque<int[]> ranges = new ArrayDeque<>();
        ranges.push(new int[] {0, n - 1});
        while (!ranges.isEmpty()) {
            int[] range = ranges.pop();
            int from = range[0];
            int to = range[1];
            if (to - from < 2) {
                continue;
            }
            double worst = -1;
            int worstIndex = -1;
            int[] a = points.get(from);
            int[] b = points.get(to);
            for (int i = from + 1; i < to; i++) {
                double d = distanceToSegment(points.get(i), a, b);
                if (d > worst) {
                    worst = d;
                    worstIndex = i;
                }
            }
            if (worst > epsilon) {
                keep[worstIndex] = true;
                ranges.push(new int[] {from, worstIndex});
                ranges.push(new int[] {worstIndex, to});
            }
        }
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (keep[i]) {
                out.add(points.get(i));
            }
        }
        return out;
    }

    /** Distance from {@code p} to the segment {@code a–b} (3D). */
    public static double distanceToSegment(int[] p, int[] a, int[] b) {
        return distanceToSegment(p[0], p[1], p[2], a[0], a[1], a[2], b[0], b[1], b[2]);
    }

    /** Distance from a point to a segment (3D). */
    public static double distanceToSegment(double px, double py, double pz,
                                           double ax, double ay, double az,
                                           double bx, double by, double bz) {
        double dx = bx - ax;
        double dy = by - ay;
        double dz = bz - az;
        double lengthSq = dx * dx + dy * dy + dz * dz;
        double t = 0;
        if (lengthSq > 0) {
            t = ((px - ax) * dx + (py - ay) * dy + (pz - az) * dz) / lengthSq;
            t = Math.max(0, Math.min(1, t));
        }
        double cx = ax + t * dx - px;
        double cy = ay + t * dy - py;
        double cz = az + t * dz - pz;
        return Math.sqrt(cx * cx + cy * cy + cz * cz);
    }

    /** Walked length of a polyline (sum of 3D segment lengths). */
    public static double length(List<int[]> points) {
        double total = 0;
        for (int i = 1; i < points.size(); i++) {
            int[] a = points.get(i - 1);
            int[] b = points.get(i);
            double dx = b[0] - a[0];
            double dy = b[1] - a[1];
            double dz = b[2] - a[2];
            total += Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        return total;
    }
}

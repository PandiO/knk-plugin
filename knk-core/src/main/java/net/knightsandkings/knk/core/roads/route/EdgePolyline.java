package net.knightsandkings.knk.core.roads.route;

import java.util.List;

/**
 * An edge's geometry decoded once for routing: the floor-block points plus the cumulative 3D
 * distance along them, so a position on the edge can be addressed by "blocks along the polyline
 * from the From node" and interpolated back to a point.
 *
 * <p>The polyline length is at most the edge's walked {@code length} (RDP shortens); routing costs
 * use the walked length, positions on the edge use polyline metres. Bukkit-free.
 */
public final class EdgePolyline {

    private final List<int[]> points;
    private final double[] cumulative;

    EdgePolyline(List<int[]> points) {
        if (points.size() < 2) {
            throw new IllegalArgumentException("a polyline needs at least two points");
        }
        this.points = points;
        this.cumulative = new double[points.size()];
        for (int i = 1; i < points.size(); i++) {
            cumulative[i] = cumulative[i - 1] + distance(points.get(i - 1), points.get(i));
        }
    }

    /** Floor-block points, From → To (unmodifiable view of the edge's geometry). */
    public List<int[]> points() {
        return points;
    }

    public int pointCount() {
        return points.size();
    }

    public int segmentCount() {
        return points.size() - 1;
    }

    /** Total polyline length in blocks. */
    public double length() {
        return cumulative[cumulative.length - 1];
    }

    /** Distance along the polyline at which point {@code index} sits. */
    public double cumulativeAt(int index) {
        return cumulative[index];
    }

    /** Length of segment {@code i} (from point {@code i} to {@code i + 1}). */
    public double segmentLength(int i) {
        return cumulative[i + 1] - cumulative[i];
    }

    /** Distance along the polyline of parameter {@code t} (0..1) inside segment {@code i}. */
    public double along(int segmentIndex, double t) {
        return cumulative[segmentIndex] + t * segmentLength(segmentIndex);
    }

    /** Index of the segment containing {@code along} (clamped; the last segment for the end). */
    public int segmentAt(double along) {
        if (along <= 0) {
            return 0;
        }
        int last = cumulative.length - 2;
        if (along >= cumulative[cumulative.length - 1]) {
            return last;
        }
        int lo = 0;
        int hi = last;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (cumulative[mid] <= along) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    /** The point {@code along} blocks from the From node (clamped to the polyline). */
    public double[] pointAt(double along) {
        if (along <= 0) {
            return toDouble(points.get(0));
        }
        if (along >= length()) {
            return toDouble(points.get(points.size() - 1));
        }
        int i = segmentAt(along);
        double segLen = segmentLength(i);
        double t = segLen <= 0 ? 0 : (along - cumulative[i]) / segLen;
        return interpolate(points.get(i), points.get(i + 1), t);
    }

    static double[] interpolate(int[] a, int[] b, double t) {
        return new double[] {a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
    }

    static double[] toDouble(int[] p) {
        return new double[] {p[0], p[1], p[2]};
    }

    static double distance(int[] a, int[] b) {
        double dx = b[0] - a[0];
        double dy = b[1] - a[1];
        double dz = b[2] - a[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    static double distance(double[] a, double[] b) {
        double dx = b[0] - a[0];
        double dy = b[1] - a[1];
        double dz = b[2] - a[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}

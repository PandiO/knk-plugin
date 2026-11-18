package net.knightsandkings.knk.core.navigation;

import java.util.List;

/**
 * A player's place on a polyline that follows them forwards and backwards with continuity (off-road navigation DESIGN
 * § 7.2.3 and § 7.5.2, decision O15): the route's {@code cursor} in {@link NavigationSession} and a walk leg's cursor on
 * its path. Pure and Bukkit-free; the points are floor points, the player's position is a floor position (feet − 1).
 *
 * <p>One step ({@link #follow}):
 * <ol>
 *   <li>The <b>local</b> candidate: the nearest point of the polyline within {@code window} blocks along it of the
 *       previous cursor. Where two stretches of that window are about equally near (two arms of a switchback, a
 *       player standing between them: {@link #BRANCH_GAP} apart along the polyline and within
 *       {@link #BRANCH_TIE} of each other's distance), the one nearer the previous cursor wins.</li>
 *   <li>The <b>far</b> candidate: the nearest point of the whole polyline. It replaces the local one only when the local
 *       one is farther than {@code leaveDistance} and the far one is not (the route's loops, § 7.5.6), or when it is
 *       nearer than the local one by more than {@code nearerMargin} (the walk path's "really nearer", § 7.2.3).</li>
 *   <li>Neither within {@code leaveDistance}: the player is off the polyline; the cursor stays where it was and the
 *       step reports the far candidate's distance.</li>
 * </ol>
 */
public final class PolylineCursor {

    /** Two candidates at least this far apart along the polyline are on different stretches (branches). */
    public static final double BRANCH_GAP = 3.0;
    /** Two branches whose distances to the player differ by at most this much are a tie: continuity decides. */
    public static final double BRANCH_TIE = 0.75;
    /** A walk leg's cursor moves at most this far along its path in one step unless the player is really nearer elsewhere (§ 7.2.3). */
    public static final double PATH_WINDOW = 3.0;
    /** "Really nearer" for a walk leg's cursor: the far point must be nearer than the local one by more than this. */
    public static final double PATH_NEARER = 1.0;

    private PolylineCursor() {
    }

    /**
     * A step's answer.
     *
     * @param along    the cursor after the step (the previous one when {@code onPolyline} is false)
     * @param distance the player's distance to the chosen point (off the polyline: to the nearest point of it)
     * @param point    the chosen point (off the polyline: the nearest point)
     * @param onPolyline the player is within {@code leaveDistance} of the polyline
     * @param jumped   the far candidate was taken
     */
    public record Step(double along, double distance, double[] point, boolean onPolyline, boolean jumped) {
    }

    /** A point of the polyline: how far along it lies and its distance to the player. */
    public record Candidate(double along, double distance, double[] point) {
    }

    /** Cumulative lengths of a polyline: {@code [0, |p0p1|, |p0p1| + |p1p2|, ...]}. */
    public static double[] cumulative(List<double[]> points) {
        double[] cumulative = new double[points.size()];
        for (int i = 1; i < points.size(); i++) {
            cumulative[i] = cumulative[i - 1] + distance(points.get(i - 1), points.get(i));
        }
        return cumulative;
    }

    /**
     * One step of the cursor (see the class description).
     *
     * @param points        the polyline (at least one point)
     * @param cumulative    {@link #cumulative(List)} of it
     * @param previous      the previous cursor (along); {@code Double.NaN}: none yet, the nearest point is taken
     * @param window        how far along the polyline the cursor may move in one step without the far rule
     * @param leaveDistance beyond this distance the player is off the polyline
     * @param nearerMargin  the far candidate replaces a local one within {@code leaveDistance} when nearer by more than
     *                      this; {@code Double.POSITIVE_INFINITY}: never
     */
    public static Step follow(List<double[]> points, double[] cumulative, double previous, double x, double y, double z,
                              double window, double leaveDistance, double nearerMargin) {
        Candidate far = nearest(points, cumulative, x, y, z, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
        if (Double.isNaN(previous)) {
            boolean on = far.distance() <= leaveDistance;
            return new Step(on ? far.along() : 0, far.distance(), far.point(), on, false);
        }
        Candidate local = local(points, cumulative, previous, x, y, z, window);
        boolean localOn = local != null && local.distance() <= leaveDistance;
        boolean farOn = far.distance() <= leaveDistance;
        if (localOn && !(far.distance() + nearerMargin < local.distance())) {
            return new Step(local.along(), local.distance(), local.point(), true, false);
        }
        if (farOn) {
            return new Step(far.along(), far.distance(), far.point(), true, true);
        }
        return new Step(previous, far.distance(), far.point(), false, false);
    }

    /**
     * The local candidate: the nearest point within {@code window} of {@code previous}; between two about equally near
     * branches the one nearer {@code previous}. Null for an empty window (cannot happen for a cursor on the polyline).
     */
    static Candidate local(List<double[]> points, double[] cumulative, double previous, double x, double y, double z,
                           double window) {
        double from = previous - window;
        double to = previous + window;
        Candidate best = null;
        Candidate second = null; // the best candidate on another branch than {@code best}
        int segments = Math.max(1, points.size() - 1);
        Candidate[] perSegment = new Candidate[segments];
        for (int i = 0; i < segments; i++) {
            perSegment[i] = segmentNearest(points, cumulative, i, x, y, z, from, to);
            if (perSegment[i] != null && (best == null || perSegment[i].distance() < best.distance())) {
                best = perSegment[i];
            }
        }
        if (best == null) {
            return null;
        }
        for (Candidate c : perSegment) {
            if (c != null && Math.abs(c.along() - best.along()) >= BRANCH_GAP
                && (second == null || c.distance() < second.distance())) {
                second = c;
            }
        }
        if (second != null && second.distance() - best.distance() <= BRANCH_TIE
            && Math.abs(second.along() - previous) < Math.abs(best.along() - previous)) {
            return second;
        }
        return best;
    }

    /** The nearest point of the polyline between {@code from} and {@code to} along it (clamped to the polyline). */
    public static Candidate nearest(List<double[]> points, double[] cumulative, double x, double y, double z,
                                    double from, double to) {
        Candidate best = null;
        int segments = Math.max(1, points.size() - 1);
        for (int i = 0; i < segments; i++) {
            Candidate c = segmentNearest(points, cumulative, i, x, y, z, from, to);
            if (c != null && (best == null || c.distance() < best.distance())) {
                best = c;
            }
        }
        if (best == null) { // a window beyond either end: the nearer end
            double total = cumulative[cumulative.length - 1];
            double along = to < 0 ? 0 : from > total ? total : Math.max(0, Math.min(total, from));
            double[] p = pointAt(points, cumulative, along);
            best = new Candidate(along, distance(p, x, y, z), p);
        }
        return best;
    }

    private static Candidate segmentNearest(List<double[]> points, double[] cumulative, int i, double x, double y,
                                            double z, double from, double to) {
        if (points.size() == 1) {
            double[] p = points.get(0);
            return from <= 0 && to >= 0 ? new Candidate(0, distance(p, x, y, z), p.clone()) : null;
        }
        double start = cumulative[i];
        double end = cumulative[i + 1];
        if (end < from || start > to) {
            return null;
        }
        double[] a = points.get(i);
        double[] b = points.get(i + 1);
        double length = end - start;
        double tMin = length <= 0 ? 0 : Math.max(0, (from - start) / length);
        double tMax = length <= 0 ? 0 : Math.min(1, (to - start) / length);
        double dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
        double len2 = dx * dx + dy * dy + dz * dz;
        double t = len2 <= 0 ? 0 : ((x - a[0]) * dx + (y - a[1]) * dy + (z - a[2]) * dz) / len2;
        t = Math.max(tMin, Math.min(tMax, t));
        double[] p = {a[0] + dx * t, a[1] + dy * t, a[2] + dz * t};
        return new Candidate(start + t * length, distance(p, x, y, z), p);
    }

    /** The point {@code along} blocks along the polyline (clamped to its ends). */
    public static double[] pointAt(List<double[]> points, double[] cumulative, double along) {
        if (points.size() == 1 || along <= 0) {
            return points.get(0).clone();
        }
        for (int i = 0; i + 1 < points.size(); i++) {
            if (cumulative[i + 1] >= along) {
                double length = cumulative[i + 1] - cumulative[i];
                double t = length <= 0 ? 0 : (along - cumulative[i]) / length;
                double[] a = points.get(i);
                double[] b = points.get(i + 1);
                return new double[] {a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
            }
        }
        return points.get(points.size() - 1).clone();
    }

    static double distance(double[] p, double x, double y, double z) {
        double dx = p[0] - x, dy = p[1] - y, dz = p[2] - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double distance(double[] a, double[] b) {
        return distance(a, b[0], b[1], b[2]);
    }
}

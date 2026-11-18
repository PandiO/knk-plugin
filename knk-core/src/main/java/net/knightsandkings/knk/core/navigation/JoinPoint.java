package net.knightsandkings.knk.core.navigation;

import java.util.List;

/**
 * Where a player off the road joins the route (off-road navigation DESIGN § 7.5.4, A19): the point of the route with
 * the least remaining cost, the walk to it plus the route length left after it, in either direction along the route.
 * Pure and Bukkit-free.
 *
 * <p>The walk is not searched here (one search per candidate would be far too costly); it is estimated as the straight
 * distance times {@link #WALK_FACTOR}. A factor of 1 would always choose the point nearest the destination (a straight
 * line is never longer than the road), so the factor stands for the detours a walk path makes and the comfort of a
 * road; at 1.5 a player 10 blocks beside a straight road joins it about 9 blocks ahead of their nearest point.
 */
public final class JoinPoint {

    /** The walk's estimated cost per block of straight distance (see the class description). */
    public static final double WALK_FACTOR = 1.5;
    /** Candidate points are this far apart along the route. */
    public static final double STEP = 1.0;

    private JoinPoint() {
    }

    /**
     * @param along    how far along the route the point lies
     * @param point    the point (floor coordinates)
     * @param cost     estimated walk plus the route left after the point
     */
    public record Choice(double along, double[] point, double cost) {
    }

    /**
     * The join point for a player at a floor position.
     *
     * @param polyline     the route's polyline (floor points)
     * @param maxDistance  points farther than this (straight, 3D) are not considered: the walk range
     * @return the start of the route when no point is within {@code maxDistance}
     */
    public static Choice choose(List<double[]> polyline, double x, double y, double z, double maxDistance) {
        double[] cumulative = PolylineCursor.cumulative(polyline);
        double total = cumulative[cumulative.length - 1];
        Choice best = null;
        int steps = (int) Math.ceil(total / STEP);
        for (int k = 0; k <= steps; k++) {
            double along = Math.min(total, k * STEP);
            double[] p = PolylineCursor.pointAt(polyline, cumulative, along);
            double walk = PolylineCursor.distance(p, x, y, z);
            if (walk > maxDistance) {
                continue;
            }
            double cost = walk * WALK_FACTOR + (total - along);
            if (best == null || cost < best.cost() - 1e-9) {
                best = new Choice(along, p, cost);
            }
        }
        if (best == null) {
            double[] start = polyline.get(0).clone();
            return new Choice(0, start, PolylineCursor.distance(start, x, y, z) * WALK_FACTOR + total);
        }
        return best;
    }
}

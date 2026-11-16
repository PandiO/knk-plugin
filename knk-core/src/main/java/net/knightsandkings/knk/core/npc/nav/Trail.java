package net.knightsandkings.knk.core.npc.nav;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The waypoint list a mover was last given for the road line, and how far along it the NPC has come (link B2,
 * PandiO/knk-workspace#671; chain report decision 34). The engine does not say which waypoint it walks to, so this
 * keeps the index itself: the waypoint nearest the feet <b>in 3D</b> from the current one on, or the one after it once
 * the NPC has passed it. Height counts in full, so a waypoint on the flight above or below on a stacked staircase is
 * never taken for the NPC's own.
 * <p>
 * Two things come from it: the handover of the next stretch is judged by the 3D length of the waypoints still to walk
 * ({@link #rest}), not by the horizontal distance to the stretch end, and the next stretch is appended to
 * {@link #remaining()} instead of replacing it. Pure; main thread.
 */
public final class Trail {

    /** Within this many blocks (3D) of a waypoint the NPC has reached it. */
    static final double REACHED = 0.6;
    /** The NPC is past a waypoint once its feet are this far beyond it along the next step. */
    static final double PASSED = 0.3;
    /** A waypoint this many blocks or more above or below the feet is not on the NPC's level. */
    static final double LEVEL = 1.5;

    private final List<NavPoint> points;
    /** The index of the waypoint the NPC walks to. */
    private int at;

    /** @param points the waypoint list given to the mover (feet positions), not empty */
    public Trail(List<NavPoint> points) {
        Objects.requireNonNull(points, "points");
        if (points.isEmpty()) {
            throw new IllegalArgumentException("a trail needs at least one waypoint");
        }
        this.points = List.copyOf(points);
    }

    /** The whole list as it was given. */
    public List<NavPoint> points() {
        return points;
    }

    /** The index of the waypoint the NPC walks to. */
    public int index() {
        return at;
    }

    /**
     * Moves the index on to where the feet are: the waypoint nearest them in 3D from the current one to the end (the
     * first of equals), or the one after that once reached or passed. Never moves back.
     */
    public void update(NavPoint feet) {
        if (feet == null || at >= points.size()) {
            return;
        }
        int best = at;
        double bestD = Double.MAX_VALUE;
        for (int k = at; k < points.size(); k++) {
            double d = dist3(feet, points.get(k));
            if (d < bestD) {
                bestD = d;
                best = k;
            }
        }
        NavPoint p = points.get(best);
        boolean sameLevel = Math.abs(feet.y() - p.y()) < LEVEL;
        if (bestD <= REACHED || (best + 1 < points.size() && sameLevel
            && beyond(p, points.get(best + 1), feet) > PASSED)) {
            best++;
        }
        at = Math.max(at, best);
    }

    /** The waypoints not yet walked: from the one the NPC walks to, to the end (empty once all are walked). */
    public List<NavPoint> remaining() {
        return at >= points.size() ? List.of() : points.subList(at, points.size());
    }

    /**
     * The 3D length still to walk: from the feet to the waypoint it walks to, then along the rest of the list. The
     * handover's measure (decision 34.1): on a stacked staircase the end of a stretch can be right above the NPC and
     * still a flight away.
     */
    public double rest(NavPoint feet) {
        if (at >= points.size()) {
            return 0;
        }
        double sum = feet == null ? 0 : dist3(feet, points.get(at));
        for (int k = at + 1; k < points.size(); k++) {
            sum += dist3(points.get(k - 1), points.get(k));
        }
        return sum;
    }

    /** The waypoints not yet walked with {@code more} after them: the next stretch appended (decision 34.1). */
    public List<NavPoint> appended(List<NavPoint> more) {
        List<NavPoint> out = new ArrayList<>(remaining());
        for (NavPoint p : more) {
            if (out.isEmpty() || !same(out.get(out.size() - 1), p)) {
                out.add(p);
            }
        }
        return out;
    }

    /** How far ahead (horizontally) the join may start from a waypoint the feet reach in a straight walk. */
    static final double PULL_BLOCKS = 3;

    /**
     * The list from the furthest of its first waypoints the feet reach in a straight walk on their own level
     * (within {@value #PULL_BLOCKS} blocks horizontally, less than a block up or down, {@link NavGeometry#straightWalkable}),
     * so the NPC does not turn back to a waypoint it is already past when the next stretch is appended. A waypoint on
     * another level or across a drop is never skipped: the look stops at the first one off the feet's level.
     */
    public static List<NavPoint> fromFeet(NavWorld world, NavPoint feet, List<NavPoint> way) {
        if (feet == null || way.size() < 2) {
            return way;
        }
        int from = 0;
        for (int k = 1; k < way.size(); k++) {
            NavPoint p = way.get(k);
            if (p.horizontalDistance(feet) > PULL_BLOCKS || Math.abs(p.y() - feet.y()) >= 1
                || Math.abs(way.get(k - 1).y() - feet.y()) >= 1) {
                break;
            }
            if (NavGeometry.straightWalkable(world, feet, p)) {
                from = k;
            }
        }
        return from == 0 ? way : way.subList(from, way.size());
    }

    private static boolean same(NavPoint a, NavPoint b) {
        return a.world().equals(b.world()) && a.blockX() == b.blockX() && a.blockZ() == b.blockZ()
            && Math.abs(a.y() - b.y()) < 0.5;
    }

    /** How far past {@code a}, along a→b (horizontal), the feet are; negative before it. */
    private static double beyond(NavPoint a, NavPoint b, NavPoint feet) {
        double dx = b.x() - a.x();
        double dz = b.z() - a.z();
        double len = Math.hypot(dx, dz);
        if (len < 1e-9) {
            return 0;
        }
        return ((feet.x() - a.x()) * dx + (feet.z() - a.z()) * dz) / len;
    }

    public static double dist3(NavPoint a, NavPoint b) {
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        double dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}

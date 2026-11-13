package net.knightsandkings.knk.core.npc.nav;

import java.util.ArrayList;
import java.util.List;

/**
 * Cuts a line of floor points into hops the engine's local path search walks one at a time (BEHAVIOR_MODEL NAV-01;
 * since decision Q8 only for off-road bits and stuck recovery): points every N blocks, each moved onto a floor an NPC
 * can stand on. Points are <b>floor points</b>: the block stood on; feet are one block higher ({@link #feet}).
 */
public final class HopPlanner {

    /** How far up or down a hop is moved to find a floor (the engine only moves a target up two blocks). */
    public static final int SNAP_RANGE = 12;
    /** A route waypoint's floor is at most this far above or below the previous one (no jump to another flight). */
    public static final int CONTINUITY_RANGE = 2;

    private HopPlanner() {
    }

    /** The feet position of an NPC standing on a floor point. */
    public static NavPoint feet(NavPoint floor) {
        return new NavPoint(floor.world(), floor.x(), floor.y() + 1, floor.z());
    }

    /** The floor point under a feet position. */
    public static NavPoint floor(NavPoint feet) {
        return new NavPoint(feet.world(), feet.x(), Math.floor(feet.y()) - 1, feet.z());
    }

    /**
     * Points every {@code spacing} blocks along a line of floor points (measured in 3D), its end included; heights
     * are interpolated and rounded to whole blocks. An empty line gives no points.
     */
    public static List<NavPoint> resample(List<NavPoint> line, double spacing) {
        if (!(spacing > 0)) {
            throw new IllegalArgumentException("spacing must be positive");
        }
        List<NavPoint> out = new ArrayList<>();
        if (line.isEmpty()) {
            return out;
        }
        double carried = 0;
        for (int i = 1; i < line.size(); i++) {
            NavPoint a = line.get(i - 1);
            NavPoint b = line.get(i);
            double len = Math.sqrt(sq(b.x() - a.x()) + sq(b.y() - a.y()) + sq(b.z() - a.z()));
            if (len == 0) {
                continue;
            }
            double along = spacing - carried;
            while (along <= len) {
                double t = along / len;
                out.add(new NavPoint(a.world(), a.x() + (b.x() - a.x()) * t, Math.round(a.y() + (b.y() - a.y()) * t),
                    a.z() + (b.z() - a.z()) * t));
                along += spacing;
            }
            carried = len - (along - spacing);
        }
        NavPoint last = line.get(line.size() - 1);
        if (out.isEmpty() || !samePlace(out.get(out.size() - 1), last)) {
            out.add(last);
        }
        return out;
    }

    /** As {@link #resample}, plus every vertex of the line, so a followed route turns exactly at the road's corners. */
    public static List<NavPoint> resampleKeepingCorners(List<NavPoint> line, double spacing) {
        List<NavPoint> out = new ArrayList<>();
        if (line.isEmpty()) {
            return out;
        }
        out.add(line.get(0));
        for (int i = 1; i < line.size(); i++) {
            for (NavPoint p : resample(List.of(line.get(i - 1), line.get(i)), spacing)) {
                if (!samePlace(out.get(out.size() - 1), p)) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    /**
     * The hops along {@code line}: {@link #resample} at {@code spacing}, each point snapped to a floor
     * ({@link #snapToFloor}). The last hop is the line's end, snapped.
     */
    public static List<NavPoint> hops(NavWorld world, List<NavPoint> line, double spacing) {
        List<NavPoint> out = new ArrayList<>();
        for (NavPoint hop : resample(line, spacing)) {
            out.add(snapToFloor(world, hop));
        }
        return out;
    }

    /**
     * An interpolated height can be in the air or inside the ground: the nearest floor within {@link #SNAP_RANGE}
     * blocks up or down (the point's own height first, then alternating up and down). Without one, or in an unloaded
     * chunk, the point as it is.
     */
    public static NavPoint snapToFloor(NavWorld world, NavPoint p) {
        int x = p.blockX();
        int z = p.blockZ();
        int y0 = (int) Math.round(p.y());
        if (!world.isLoaded(p.world(), x, z)) {
            return p;
        }
        for (int d = 0; d <= SNAP_RANGE; d++) {
            if (world.isStandableFloor(p.world(), x, y0 + d, z)) {
                return new NavPoint(p.world(), p.x(), y0 + d, p.z());
            }
            if (d > 0 && world.isStandableFloor(p.world(), x, y0 - d, z)) {
                return new NavPoint(p.world(), p.x(), y0 - d, p.z());
            }
        }
        return p;
    }

    /**
     * Floor continuity (decision Q8): the floor for a route point within {@link #CONTINUITY_RANGE} blocks of
     * {@code refY} (the previous waypoint's floor), the one nearest the point's own height, so on stacked floors (a
     * U-turn staircase) the route never jumps to the flight above or below. Without one there, {@link #snapToFloor}.
     */
    public static NavPoint snapNear(NavWorld world, NavPoint p, int refY) {
        int x = p.blockX();
        int z = p.blockZ();
        if (!world.isLoaded(p.world(), x, z)) {
            return p;
        }
        int best = Integer.MIN_VALUE;
        for (int y = refY - CONTINUITY_RANGE; y <= refY + CONTINUITY_RANGE; y++) {
            if (world.isStandableFloor(p.world(), x, y, z)
                && (best == Integer.MIN_VALUE || Math.abs(y - p.y()) < Math.abs(best - p.y()))) {
                best = y;
            }
        }
        return best == Integer.MIN_VALUE ? snapToFloor(world, p) : new NavPoint(p.world(), p.x(), best, p.z());
    }

    private static boolean samePlace(NavPoint a, NavPoint b) {
        return Math.abs(a.x() - b.x()) < 1e-6 && Math.abs(a.y() - b.y()) < 1e-6 && Math.abs(a.z() - b.z()) < 1e-6;
    }

    private static double sq(double v) {
        return v * v;
    }
}

package net.knightsandkings.knk.core.navigation.shape;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The free width across a walking line at one point (implementation plan 4c.4 item 1, link M1: diagnostics only,
 * the {@code # corridor:} lines of {@code /knk npc nav trace}): how far a body's centre line could move to the left
 * and to the right of the point, square to the line's heading there, before it reaches a face that stops a body at
 * feet or head height ({@link NavWorld#blocksBody}: a wall, a fence, a post, a full-block step) or a drop (no floor
 * within a block of the point's floor). Each side is capped at {@link #MAX_HALF} (the decided
 * {@code corridor-half-width}, 4c.6 question 3; no key until M2). Exact block faces: the ray across the line is
 * walked cell by cell. Pure, over the {@link NavWorld} port; main thread.
 *
 * @param left  free blocks to the left of the heading
 * @param right free blocks to the right of the heading
 */
public record FreeWidth(double left, double right) {

    /** The most blocks looked at to either side. */
    public static final double MAX_HALF = 4.0;

    /** Left plus right: the free width across the line. */
    public double total() {
        return left + right;
    }

    /** {@code "2.40 1.10 1.30"}: total, left, right. */
    public String describe() {
        return String.format(Locale.ROOT, "%.2f %.2f %.2f", total(), left, right);
    }

    /**
     * The free width at each point of a line of feet positions, square to the line's heading there (from the point
     * before to the point after; the end points use their one neighbour). A point whose own cell stops a body has 0
     * to either side.
     */
    public static List<FreeWidth> along(NavWorld world, List<NavPoint> feet) {
        List<FreeWidth> out = new ArrayList<>(feet.size());
        for (int i = 0; i < feet.size(); i++) {
            NavPoint before = feet.get(Math.max(0, i - 1));
            NavPoint after = feet.get(Math.min(feet.size() - 1, i + 1));
            out.add(at(world, feet.get(i), after.x() - before.x(), after.z() - before.z()));
        }
        return out;
    }

    /**
     * The free width at a feet position across the heading {@code (hx, hz)} (any length; none: across the x axis,
     * as if heading east).
     */
    public static FreeWidth at(NavWorld world, NavPoint feet, double hx, double hz) {
        double len = Math.hypot(hx, hz);
        if (len < 1e-9) {
            hx = 1;
            hz = 0;
        } else {
            hx /= len;
            hz /= len;
        }
        int feetY = feet.blockY();
        if (!free(world, feet.world(), feet.blockX(), feetY, feet.blockZ())) {
            return new FreeWidth(0, 0);
        }
        // to the right of heading (hx, hz) is (-hz, hx), as in the group's lanes
        double right = reach(world, feet, -hz, hx, feetY);
        double left = reach(world, feet, hz, -hx, feetY);
        return new FreeWidth(left, right);
    }

    /** How far from {@code feet} along the unit direction {@code (dx, dz)} the cells stay free, capped at MAX_HALF. */
    private static double reach(NavWorld world, NavPoint feet, double dx, double dz, int feetY) {
        int cx = feet.blockX();
        int cz = feet.blockZ();
        int stepX = dx > 0 ? 1 : dx < 0 ? -1 : 0;
        int stepZ = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        // the distance along the ray to the next x face and z face, and between faces (grid traversal)
        double tMaxX = stepX == 0 ? Double.MAX_VALUE : ((stepX > 0 ? cx + 1 - feet.x() : feet.x() - cx) / Math.abs(dx));
        double tMaxZ = stepZ == 0 ? Double.MAX_VALUE : ((stepZ > 0 ? cz + 1 - feet.z() : feet.z() - cz) / Math.abs(dz));
        double tDeltaX = stepX == 0 ? Double.MAX_VALUE : 1 / Math.abs(dx);
        double tDeltaZ = stepZ == 0 ? Double.MAX_VALUE : 1 / Math.abs(dz);
        while (true) {
            double t;
            if (tMaxX < tMaxZ) {
                t = tMaxX;
                cx += stepX;
                tMaxX += tDeltaX;
            } else {
                t = tMaxZ;
                cz += stepZ;
                tMaxZ += tDeltaZ;
            }
            if (t >= MAX_HALF) {
                return MAX_HALF;
            }
            if (!free(world, feet.world(), cx, feetY, cz)) {
                return t;
            }
        }
    }

    /**
     * Whether a body can stand in a cell beside the line at the line's feet height: nothing stops it at feet or head
     * height there and it has a floor within a block of the line's floor (no drop).
     */
    static boolean free(NavWorld world, String w, int x, int feetY, int z) {
        if (!world.isLoaded(w, x, z) || NavGeometry.wallAt(world, w, x, feetY, z)) {
            return false;
        }
        int floorY = feetY - 1;
        return world.isStandableFloor(w, x, floorY, z) || world.isStandableFloor(w, x, floorY + 1, z)
            || world.isStandableFloor(w, x, floorY - 1, z);
    }
}

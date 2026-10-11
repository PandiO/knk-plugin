package net.knightsandkings.knk.core.gates.target;

import java.util.OptionalDouble;

/**
 * An axis-aligned box in world coordinates (block corners: a block at x=5 spans 5..6), used as a
 * gate door's "region" by the gate command targets (KNG-78 {@code here}, KNG-79 look-at).
 *
 * @param minX lowest x corner
 * @param minY lowest y corner
 * @param minZ lowest z corner
 * @param maxX highest x corner
 * @param maxY highest y corner
 * @param maxZ highest z corner
 */
public record GateBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {

    public GateBox {
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("min corner must not exceed max corner");
        }
    }

    /** The one-block box of the block at (x, y, z). */
    public static GateBox ofBlock(int x, int y, int z) {
        return new GateBox(x, y, z, x + 1, y + 1, z + 1);
    }

    /** The smallest box holding both; {@code other} may be null. */
    public GateBox union(GateBox other) {
        if (other == null) {
            return this;
        }
        return new GateBox(Math.min(minX, other.minX), Math.min(minY, other.minY), Math.min(minZ, other.minZ),
            Math.max(maxX, other.maxX), Math.max(maxY, other.maxY), Math.max(maxZ, other.maxZ));
    }

    /**
     * Distance from a point to the closest point of the box: the point clamped into the box.
     * A point inside (or on the surface) is at distance 0.
     */
    public double distanceTo(double x, double y, double z) {
        double dx = x - clamp(x, minX, maxX);
        double dy = y - clamp(y, minY, maxY);
        double dz = z - clamp(z, minZ, maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Where a ray first enters the box (slab method): the distance along the ray, in units of the
     * direction's length, or empty when the ray misses or the box lies behind the origin. A ray
     * starting inside the box enters at 0.
     */
    public OptionalDouble rayEntry(double ox, double oy, double oz, double dx, double dy, double dz) {
        double tMin = 0;
        double tMax = Double.POSITIVE_INFINITY;
        double[] origin = {ox, oy, oz};
        double[] direction = {dx, dy, dz};
        double[] min = {minX, minY, minZ};
        double[] max = {maxX, maxY, maxZ};
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(direction[axis]) < 1e-12) {
                if (origin[axis] < min[axis] || origin[axis] > max[axis]) {
                    return OptionalDouble.empty();
                }
                continue;
            }
            double t1 = (min[axis] - origin[axis]) / direction[axis];
            double t2 = (max[axis] - origin[axis]) / direction[axis];
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
            if (tMin > tMax) {
                return OptionalDouble.empty();
            }
        }
        return OptionalDouble.of(tMin);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}

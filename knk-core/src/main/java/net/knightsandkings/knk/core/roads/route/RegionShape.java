package net.knightsandkings.knk.core.roads.route;

import net.knightsandkings.knk.core.util.Polygon2D;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A Bukkit-free WorldGuard region (DESIGN §6.3): a polygon in x/z (the region's points) with a
 * y band, or a cuboid (four corners). Coordinates are block coordinates, bounds inclusive.
 * Containment of a network point uses the <b>feet</b> block ({@code floorY + 1}) against the y
 * band, since regions are drawn around where players stand. Geometry via {@link Polygon2D} (R2).
 */
public final class RegionShape {

    private final List<double[]> points;
    private final int minY;
    private final int maxY;
    private final double minX, maxX, minZ, maxZ;

    private RegionShape(List<double[]> points, int minY, int maxY) {
        if (points.size() < 3) {
            throw new IllegalArgumentException("a region needs at least three points");
        }
        if (minY > maxY) {
            throw new IllegalArgumentException("minY must be <= maxY");
        }
        List<double[]> copy = new ArrayList<>(points.size());
        double x0 = Double.POSITIVE_INFINITY, x1 = Double.NEGATIVE_INFINITY;
        double z0 = Double.POSITIVE_INFINITY, z1 = Double.NEGATIVE_INFINITY;
        for (double[] p : points) {
            if (p == null || p.length != 2) {
                throw new IllegalArgumentException("polygon points must be {x, z}");
            }
            copy.add(p.clone());
            x0 = Math.min(x0, p[0]);
            x1 = Math.max(x1, p[0]);
            z0 = Math.min(z0, p[1]);
            z1 = Math.max(z1, p[1]);
        }
        this.points = Collections.unmodifiableList(copy);
        this.minY = minY;
        this.maxY = maxY;
        this.minX = x0;
        this.maxX = x1;
        this.minZ = z0;
        this.maxZ = z1;
    }

    /** A polygonal region: {@code ProtectedPolygonalRegion.getPoints()} as {@code {x, z}} + its y band. */
    public static RegionShape polygon(List<double[]> points, int minY, int maxY) {
        return new RegionShape(points, minY, maxY);
    }

    /** A cuboid region from its inclusive min/max corners. */
    public static RegionShape cuboid(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        if (minX > maxX || minZ > maxZ) {
            throw new IllegalArgumentException("min corner must be <= max corner");
        }
        return new RegionShape(List.of(new double[] {minX, minZ}, new double[] {maxX, minZ},
            new double[] {maxX, maxZ}, new double[] {minX, maxZ}), minY, maxY);
    }

    public List<double[]> points() {
        return points;
    }

    public int minY() {
        return minY;
    }

    public int maxY() {
        return maxY;
    }

    /** Whether the column {@code (x, z)} is inside (boundary inclusive). */
    public boolean containsColumn(double x, double z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ && Polygon2D.contains(x, z, points);
    }

    /** Whether a position with feet at {@code y} is inside. */
    public boolean contains(double x, double y, double z) {
        return y >= minY && y <= maxY && containsColumn(x, z);
    }

    /**
     * Whether a <b>player</b> with feet at {@code (x, y, z)} is inside, by the block the feet are in
     * - WorldGuard's rule: a player at {@code x = 120.7} stands in block 120, inside a region whose
     * max x is 120, and feet on a slab at {@code y = 70.5} stand in block 70. {@link #contains}
     * stays geometric for network points (the road/region crossings are bisected on it).
     */
    public boolean containsFeet(double x, double y, double z) {
        return contains(Math.floor(x), Math.floor(y), Math.floor(z));
    }

    /** Whether a network point (floor block, feet at {@code floorY + 1}) is inside. */
    public boolean containsFloor(double x, double floorY, double z) {
        return contains(x, floorY + 1, z);
    }

    /**
     * Distance from a floor position to the region: 0 inside; otherwise the horizontal distance to
     * the boundary (0 when the column is inside) combined with the vertical distance to the y band.
     */
    public double distanceFromFloor(double x, double floorY, double z) {
        double feet = floorY + 1;
        double horizontal = containsColumn(x, z) ? 0 : Polygon2D.distanceToBoundary(x, z, points);
        double vertical = feet < minY ? minY - feet : (feet > maxY ? feet - maxY : 0);
        return Math.hypot(horizontal, vertical);
    }

    /**
     * The point of the region closest to a floor position, as a floor position: the column's
     * closest boundary point (or the column itself when inside) with the floor y clamped into the
     * band.
     */
    public double[] closestPointFromFloor(double x, double floorY, double z) {
        double[] column = containsColumn(x, z) ? new double[] {x, z} : Polygon2D.closestPointOnBoundary(x, z, points);
        double feet = Math.max(minY, Math.min(maxY, floorY + 1));
        return new double[] {column[0], feet - 1, column[1]};
    }

    /** Centre of the bounding box (floor y of the band's middle), for "how far is the region". */
    public double[] centre() {
        return new double[] {(minX + maxX) / 2, (minY + maxY) / 2.0 - 1, (minZ + maxZ) / 2};
    }
}

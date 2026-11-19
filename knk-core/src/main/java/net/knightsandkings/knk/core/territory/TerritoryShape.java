package net.knightsandkings.knk.core.territory;

import java.util.ArrayList;
import java.util.List;

import net.knightsandkings.knk.core.map.OutlinePoint;
import net.knightsandkings.knk.core.map.RegionOutline;

/**
 * The 2D shape of a source's regions (main region and sub-regions), for the distance from a block column to the nearest
 * region edge (G11). A region is its outline as WorldGuard gives it (a cuboid's four corners, a polygon's points; block
 * coordinates, inclusive), so a column inside or on the outline is at distance 0, and a column outside is at the
 * Euclidean distance from its block coordinates to the nearest outline segment. Bukkit-free and immutable.
 */
public final class TerritoryShape {

    private final List<int[]> xs = new ArrayList<>();
    private final List<int[]> zs = new ArrayList<>();
    private int minX = Integer.MAX_VALUE;
    private int minZ = Integer.MAX_VALUE;
    private int maxX = Integer.MIN_VALUE;
    private int maxZ = Integer.MIN_VALUE;

    private TerritoryShape() {
    }

    /** A shape of the given outlines; outlines with fewer than three points are left out. Null when none is left. */
    public static TerritoryShape of(List<RegionOutline> outlines) {
        TerritoryShape shape = new TerritoryShape();
        for (RegionOutline outline : outlines) {
            if (outline == null || outline.points().size() < 3) {
                continue;
            }
            List<OutlinePoint> points = outline.points();
            int[] px = new int[points.size()];
            int[] pz = new int[points.size()];
            for (int i = 0; i < points.size(); i++) {
                px[i] = points.get(i).x();
                pz[i] = points.get(i).z();
                shape.minX = Math.min(shape.minX, px[i]);
                shape.maxX = Math.max(shape.maxX, px[i]);
                shape.minZ = Math.min(shape.minZ, pz[i]);
                shape.maxZ = Math.max(shape.maxZ, pz[i]);
            }
            shape.xs.add(px);
            shape.zs.add(pz);
        }
        return shape.xs.isEmpty() ? null : shape;
    }

    public int minX() {
        return minX;
    }

    public int minZ() {
        return minZ;
    }

    public int maxX() {
        return maxX;
    }

    public int maxZ() {
        return maxZ;
    }

    /** The distance from point (x, z) to the shape: 0 inside or on an outline, else to the nearest outline segment. */
    public double distance(double x, double z) {
        double best = Double.MAX_VALUE;
        for (int p = 0; p < xs.size(); p++) {
            int[] px = xs.get(p);
            int[] pz = zs.get(p);
            if (inside(px, pz, x, z)) {
                return 0;
            }
            for (int i = 0, j = px.length - 1; i < px.length; j = i++) {
                best = Math.min(best, segmentDistance(x, z, px[j], pz[j], px[i], pz[i]));
                if (best == 0) {
                    return 0;
                }
            }
        }
        return best;
    }

    /** Even-odd ray cast; a point on an edge counts as inside through {@link #distance}'s segment test (distance 0). */
    private static boolean inside(int[] px, int[] pz, double x, double z) {
        boolean inside = false;
        for (int i = 0, j = px.length - 1; i < px.length; j = i++) {
            if ((pz[i] > z) != (pz[j] > z)) {
                double crossX = px[j] + (z - pz[j]) * (double) (px[i] - px[j]) / (pz[i] - pz[j]);
                if (x < crossX) {
                    inside = !inside;
                }
            }
        }
        return inside;
    }

    static double segmentDistance(double x, double z, double ax, double az, double bx, double bz) {
        double dx = bx - ax;
        double dz = bz - az;
        double lengthSquared = dx * dx + dz * dz;
        double t = lengthSquared == 0 ? 0 : ((x - ax) * dx + (z - az) * dz) / lengthSquared;
        t = Math.max(0, Math.min(1, t));
        double cx = ax + t * dx - x;
        double cz = az + t * dz - z;
        return Math.sqrt(cx * cx + cz * cz);
    }
}

package net.knightsandkings.knk.core.production.survey;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The volume of a production area (PRODUCTION_KINDS § 3.1): a cuboid, a polygon with a y range, or a radius (a vertical
 * cylinder) around a centre. A WorldGuard region is turned into one of these by the Paper adapter. Immutable, so a
 * survey thread may test positions while the main thread goes on.
 */
public sealed interface AreaShape permits AreaShape.Cuboid, AreaShape.Polygon, AreaShape.Radius {

    boolean contains(int x, int y, int z);

    int minX();

    int minY();

    int minZ();

    int maxX();

    int maxY();

    int maxZ();

    /** The same shape with its y range clamped to {@code [minY, maxY]} (world limits). */
    AreaShape clampY(int minY, int maxY);

    /** Block columns (x, z) inside the shape. */
    default long columnCount() {
        long count = 0;
        for (int x = minX(); x <= maxX(); x++) {
            for (int z = minZ(); z <= maxZ(); z++) {
                if (containsColumn(x, z)) {
                    count++;
                }
            }
        }
        return count;
    }

    /** Blocks inside the shape: columns × height (what {@code production.surveys.max-area-blocks} caps). */
    default long blockCount() {
        return columnCount() * (long) Math.max(0, maxY() - minY() + 1);
    }

    /** Whether the column (x, z) is inside, ignoring y. */
    boolean containsColumn(int x, int z);

    /** Width × depth of the bounding box in chunks. */
    default long chunkCount() {
        return (long) ((maxX() >> 4) - (minX() >> 4) + 1) * ((maxZ() >> 4) - (minZ() >> 4) + 1);
    }

    record Cuboid(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) implements AreaShape {
        public Cuboid {
            if (minX > maxX) { int t = minX; minX = maxX; maxX = t; }
            if (minY > maxY) { int t = minY; minY = maxY; maxY = t; }
            if (minZ > maxZ) { int t = minZ; minZ = maxZ; maxZ = t; }
        }

        @Override
        public boolean contains(int x, int y, int z) {
            return y >= minY && y <= maxY && containsColumn(x, z);
        }

        @Override
        public boolean containsColumn(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        @Override
        public long columnCount() {
            return (long) (maxX - minX + 1) * (maxZ - minZ + 1);
        }

        @Override
        public AreaShape clampY(int low, int high) {
            return new Cuboid(minX, Math.max(minY, low), minZ, maxX, Math.min(maxY, high), maxZ);
        }
    }

    /** A polygon of block columns (WorldGuard semantics: the outline itself is inside) between minY and maxY. */
    record Polygon(List<int[]> points, int minY, int maxY, int minX, int minZ, int maxX, int maxZ) implements AreaShape {
        public Polygon {
            Objects.requireNonNull(points, "points");
            List<int[]> copy = new ArrayList<>();
            for (int[] p : points) {
                copy.add(new int[] {p[0], p[1]});
            }
            points = List.copyOf(copy);
        }

        public static Polygon of(List<int[]> points, int minY, int maxY) {
            if (points == null || points.size() < 3) {
                throw new IllegalArgumentException("a polygon needs at least 3 points");
            }
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (int[] p : points) {
                minX = Math.min(minX, p[0]);
                maxX = Math.max(maxX, p[0]);
                minZ = Math.min(minZ, p[1]);
                maxZ = Math.max(maxZ, p[1]);
            }
            return new Polygon(points, Math.min(minY, maxY), Math.max(minY, maxY), minX, minZ, maxX, maxZ);
        }

        @Override
        public boolean contains(int x, int y, int z) {
            return y >= minY && y <= maxY && containsColumn(x, z);
        }

        /** The point-in-polygon test of WorldGuard's {@code ProtectedPolygonalRegion}: edges count as inside. */
        @Override
        public boolean containsColumn(int targetX, int targetZ) {
            if (targetX < minX || targetX > maxX || targetZ < minZ || targetZ > maxZ) {
                return false;
            }
            boolean inside = false;
            int npoints = points.size();
            int xOld = points.get(npoints - 1)[0];
            int zOld = points.get(npoints - 1)[1];
            for (int[] point : points) {
                int xNew = point[0];
                int zNew = point[1];
                if (xNew == targetX && zNew == targetZ) {
                    return true;
                }
                int x1, z1, x2, z2;
                if (xNew > xOld) {
                    x1 = xOld; x2 = xNew; z1 = zOld; z2 = zNew;
                } else {
                    x1 = xNew; x2 = xOld; z1 = zNew; z2 = zOld;
                }
                if (x1 <= targetX && targetX <= x2) {
                    long crossProduct = ((long) targetZ - (long) z1) * (long) (x2 - x1)
                            - ((long) z2 - (long) z1) * (long) (targetX - x1);
                    if (crossProduct == 0) {
                        if ((z1 <= targetZ) == (targetZ <= z2)) {
                            return true;
                        }
                    } else if (crossProduct < 0 && (x1 != targetX)) {
                        inside = !inside;
                    }
                }
                xOld = xNew;
                zOld = zNew;
            }
            return inside;
        }

        @Override
        public AreaShape clampY(int low, int high) {
            return new Polygon(points, Math.max(minY, low), Math.min(maxY, high), minX, minZ, maxX, maxZ);
        }
    }

    /** Every column whose centre lies within {@code radius} blocks (horizontally) of the centre column, minY to maxY. */
    record Radius(int centerX, int centerZ, int radius, int minY, int maxY) implements AreaShape {
        public Radius {
            if (radius < 0) {
                throw new IllegalArgumentException("radius must be 0 or more");
            }
            if (minY > maxY) { int t = minY; minY = maxY; maxY = t; }
        }

        @Override
        public boolean contains(int x, int y, int z) {
            return y >= minY && y <= maxY && containsColumn(x, z);
        }

        @Override
        public boolean containsColumn(int x, int z) {
            long dx = x - centerX;
            long dz = z - centerZ;
            return dx * dx + dz * dz <= (long) radius * radius;
        }

        @Override
        public int minX() {
            return centerX - radius;
        }

        @Override
        public int maxX() {
            return centerX + radius;
        }

        @Override
        public int minZ() {
            return centerZ - radius;
        }

        @Override
        public int maxZ() {
            return centerZ + radius;
        }

        @Override
        public AreaShape clampY(int low, int high) {
            return new Radius(centerX, centerZ, radius, Math.max(minY, low), Math.min(maxY, high));
        }
    }
}

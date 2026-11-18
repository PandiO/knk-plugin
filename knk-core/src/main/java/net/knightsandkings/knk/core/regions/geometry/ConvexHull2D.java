package net.knightsandkings.knk.core.regions.geometry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The convex hull of block columns (KNG-257): turns a round WorldEdit selection (cylinder, ellipsoid, convex polyhedron)
 * into the corners of a WorldGuard polygon. WorldGuard counts a polygon's edges as inside, so the hull of the selected
 * columns covers every one of them. Andrew's monotone chain; collinear points are dropped, the result runs
 * counter-clockwise (x right, z down) from the lowest x (then z).
 */
public final class ConvexHull2D {

    /** A block column. */
    public record Point(int x, int z) {
    }

    private ConvexHull2D() {
    }

    /** The hull's corners; fewer than three points (a line, a dot) come back as they are, without duplicates. */
    public static List<Point> of(List<Point> points) {
        List<Point> sorted = new ArrayList<>(new LinkedHashSet<>(points));
        sorted.sort(Comparator.comparingInt(Point::x).thenComparingInt(Point::z));
        if (sorted.size() < 3) {
            return List.copyOf(sorted);
        }
        List<Point> hull = new ArrayList<>();
        for (Point point : sorted) {
            while (hull.size() >= 2 && cross(hull.get(hull.size() - 2), hull.get(hull.size() - 1), point) <= 0) {
                hull.remove(hull.size() - 1);
            }
            hull.add(point);
        }
        int lower = hull.size() + 1;
        for (int i = sorted.size() - 2; i >= 0; i--) {
            Point point = sorted.get(i);
            while (hull.size() >= lower && cross(hull.get(hull.size() - 2), hull.get(hull.size() - 1), point) <= 0) {
                hull.remove(hull.size() - 1);
            }
            hull.add(point);
        }
        hull.remove(hull.size() - 1);
        return List.copyOf(hull);
    }

    private static long cross(Point o, Point a, Point b) {
        return (long) (a.x() - o.x()) * (b.z() - o.z()) - (long) (a.z() - o.z()) * (b.x() - o.x());
    }
}

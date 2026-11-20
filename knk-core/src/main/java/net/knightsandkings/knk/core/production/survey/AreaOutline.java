package net.knightsandkings.knk.core.production.survey;

import java.util.ArrayList;
import java.util.List;

/**
 * The outline of an area as points on a horizontal plane (for {@code /knk production area show}, PRODUCTION_KINDS
 * § 3.3): the cuboid's rectangle, the polygon's edges, the radius's circle, about {@code step} blocks apart, at block
 * centres. Bukkit-free; the Paper side draws particles at these points.
 */
public final class AreaOutline {

    private AreaOutline() {
    }

    /** Points (x, z) along the outline, at most {@code maxPoints} (the step widens to stay under it). */
    public static List<double[]> points(AreaShape shape, double step, int maxPoints) {
        double s = Math.max(0.25, step);
        for (int attempt = 0; attempt < 8; attempt++) {
            List<double[]> points = build(shape, s);
            if (points.size() <= maxPoints) {
                return points;
            }
            s *= 2;
        }
        return build(shape, s);
    }

    private static List<double[]> build(AreaShape shape, double step) {
        List<double[]> points = new ArrayList<>();
        switch (shape) {
            case AreaShape.Cuboid c -> {
                double x1 = c.minX(), z1 = c.minZ(), x2 = c.maxX() + 1, z2 = c.maxZ() + 1;
                line(points, x1, z1, x2, z1, step);
                line(points, x2, z1, x2, z2, step);
                line(points, x2, z2, x1, z2, step);
                line(points, x1, z2, x1, z1, step);
            }
            case AreaShape.Polygon p -> {
                List<int[]> corners = p.points();
                for (int i = 0; i < corners.size(); i++) {
                    int[] a = corners.get(i);
                    int[] b = corners.get((i + 1) % corners.size());
                    line(points, a[0] + 0.5, a[1] + 0.5, b[0] + 0.5, b[1] + 0.5, step);
                }
            }
            case AreaShape.Radius r -> {
                double radius = r.radius() + 0.5;
                int count = Math.max(8, (int) Math.ceil(2 * Math.PI * radius / step));
                for (int i = 0; i < count; i++) {
                    double angle = 2 * Math.PI * i / count;
                    points.add(new double[] {r.centerX() + 0.5 + radius * Math.cos(angle),
                            r.centerZ() + 0.5 + radius * Math.sin(angle)});
                }
            }
        }
        return points;
    }

    private static void line(List<double[]> points, double x1, double z1, double x2, double z2, double step) {
        double length = Math.hypot(x2 - x1, z2 - z1);
        int count = Math.max(1, (int) Math.ceil(length / step));
        for (int i = 0; i < count; i++) {
            double t = (double) i / count;
            points.add(new double[] {x1 + (x2 - x1) * t, z1 + (z2 - z1) * t});
        }
    }
}

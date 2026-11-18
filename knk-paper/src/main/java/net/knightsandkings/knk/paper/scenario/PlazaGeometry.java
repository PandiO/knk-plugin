package net.knightsandkings.knk.paper.scenario;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Plane geometry for the plaza scenario's reference line (link M5): the shortest way between two points that keeps a
 * clearance from one axis-aligned box (the well). A visibility graph over the two ends and points on the clearance's
 * circles round the box's corners, searched with Dijkstra. Pure.
 */
public final class PlazaGeometry {

    /** Steps per corner arc (about 4 degrees each over the corner's outward quarter turn and a little beyond). */
    private static final int ARC = 24;

    private PlazaGeometry() {
    }

    /** The distance from a point to the box [x1, x2] × [z1, z2]. */
    public static double toBox(double x, double z, double x1, double z1, double x2, double z2) {
        double dx = Math.max(Math.max(x1 - x, 0), x - x2);
        double dz = Math.max(Math.max(z1 - z, 0), z - z2);
        return Math.hypot(dx, dz);
    }

    /** The least distance from the segment a→b to the box (sampled every 0.01 block). */
    static double segmentToBox(double ax, double az, double bx, double bz, double x1, double z1, double x2, double z2) {
        double len = Math.hypot(bx - ax, bz - az);
        int n = Math.max(1, (int) Math.ceil(len / 0.01));
        double best = Double.MAX_VALUE;
        for (int i = 0; i <= n; i++) {
            double t = (double) i / n;
            best = Math.min(best, toBox(ax + (bx - ax) * t, az + (bz - az) * t, x1, z1, x2, z2));
        }
        return best;
    }

    /** The length of the shortest way from a to b keeping {@code clearance} from the box. */
    public static double shortestRound(double ax, double az, double bx, double bz, double x1, double z1, double x2,
                                       double z2, double clearance) {
        List<double[]> nodes = new ArrayList<>();
        nodes.add(new double[] {ax, az});
        nodes.add(new double[] {bx, bz});
        // on a polygon round each corner's circle, so a step from one arc point to the next stays outside it
        double r = clearance / Math.cos(Math.toRadians(98.0 / ARC / 2)) + 1e-6;
        double[][] corners = {{x1, z1, Math.PI}, {x2, z1, 1.5 * Math.PI}, {x2, z2, 0}, {x1, z2, 0.5 * Math.PI}};
        for (double[] c : corners) {
            for (int k = 0; k <= ARC; k++) {
                double angle = c[2] + Math.toRadians(-4 + k * 98.0 / ARC); // the corner's outward quarter, and a bit
                nodes.add(new double[] {c[0] + r * Math.cos(angle), c[1] + r * Math.sin(angle)});
            }
        }
        int n = nodes.size();
        double[] dist = new double[n];
        boolean[] done = new boolean[n];
        Arrays.fill(dist, Double.MAX_VALUE);
        dist[0] = 0;
        for (int round = 0; round < n; round++) {
            int u = -1;
            for (int i = 0; i < n; i++) {
                if (!done[i] && (u < 0 || dist[i] < dist[u])) {
                    u = i;
                }
            }
            if (u < 0 || dist[u] == Double.MAX_VALUE) {
                break;
            }
            done[u] = true;
            if (u == 1) {
                return dist[1];
            }
            double[] p = nodes.get(u);
            for (int v = 0; v < n; v++) {
                if (done[v]) {
                    continue;
                }
                double[] q = nodes.get(v);
                double d = Math.hypot(q[0] - p[0], q[1] - p[1]);
                if (dist[u] + d < dist[v] - 1e-12
                    && segmentToBox(p[0], p[1], q[0], q[1], x1, z1, x2, z2) >= clearance - 1e-4) {
                    dist[v] = dist[u] + d;
                }
            }
        }
        return dist[1];
    }
}

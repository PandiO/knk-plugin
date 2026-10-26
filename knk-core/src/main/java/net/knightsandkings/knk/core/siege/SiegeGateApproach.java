package net.knightsandkings.knk.core.siege;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Where a siege member should be navigated for an objective whose capture point lies behind a closed gate
 * (alpha test run 1, PandiO/knk-workspace#176). Bukkit-free.
 *
 * <p>The cause of #176: the Northern Gate objective of the alpha scenario has its capture point on the gatehouse,
 * between the outer and the inner door and 8 blocks above the road. An attacker on the island is within
 * {@code max-snap-distance} of it, so navigation walks there directly; with the gate closed the walk search finds
 * no way in and its partial path leads to the reachable spot <i>nearest in a straight line</i> to the gatehouse top -
 * the crane outside the walls. The developer's rule (KNG-234, 2026-10-22) is that an attacker bound for an
 * objective behind a closed gate is guided to the gate. So the destination becomes the gate itself:
 *
 * <ul>
 *   <li>Of the gate's closed, standing doors, those with the capture point behind them as seen from the team's
 *       side (its nearest spawnpoint) - the doors in the way. Doors already open or destroyed don't count, so
 *       once the outer door is down the inner one is next, and once nothing stands in the way the capture point
 *       itself is the destination again.</li>
 *   <li>The one nearest the team's side: the outer door for attackers.</li>
 *   <li>Its approach: on the team's side, 1.5 blocks beyond the door - or beyond the far end of the door's open
 *       position when that lies on the team's side (a drawbridge lowered over the moat), so the point is on the bank,
 *       not over the water - at the door's bottom.</li>
 * </ul>
 * A door's "side" is the horizontal normal of its plane: the principal axis of its closed footprint in plan view
 * (portcullis, double door, diagonal door alike); a door one block wide has none, and the direction to the side is
 * used as the normal.
 */
public final class SiegeGateApproach {

    private SiegeGateApproach() {
    }

    /** How far the capture point may lie on the team's side of a door's plane and the door still count as in the way. */
    static final double IN_THE_WAY_TOLERANCE = 1.0;
    /** How far beyond the door (or its open position) the approach point lies. */
    static final double STAND_OFF = 1.5;

    /**
     * A closed, standing door.
     *
     * @param name   the door's name, for messages
     * @param closed its closed footprint, block positions {x, y, z}
     * @param open   its open footprint, block positions (may be empty)
     */
    public record Door(String name, List<int[]> closed, List<int[]> open) {
        public Door {
            closed = closed == null ? List.of() : List.copyOf(closed);
            open = open == null ? List.of() : List.copyOf(open);
        }
    }

    /** Where to head: feet coordinates in front of {@code door}. */
    public record Approach(String door, double x, double y, double z) {
    }

    /**
     * @param closedDoors the gate's doors that are closed and standing now
     * @param capture     the capture point {x, y, z}
     * @param sides       where the team comes from: its spawnpoints {x, z}
     * @return the approach in front of the door in the way nearest to the team's side, or empty when no closed door
     *     stands between the team and the capture point (navigate to the capture point)
     */
    public static Optional<Approach> approach(List<Door> closedDoors, double[] capture, List<double[]> sides) {
        Objects.requireNonNull(capture, "capture");
        if (closedDoors == null || closedDoors.isEmpty() || sides == null || sides.isEmpty()) {
            return Optional.empty();
        }
        Door best = null;
        double[] bestCentre = null;
        double[] bestNormal = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Door door : closedDoors) {
            if (door.closed().isEmpty()) {
                continue;
            }
            double[] c = centre(door.closed());
            double[] side = nearest(sides, c);
            double vx = side[0] - c[0];
            double vz = side[1] - c[1];
            double[] normal = normalTowards(door.closed(), c, vx, vz);
            if (normal == null) {
                continue;
            }
            double captureSide = (capture[0] - c[0]) * normal[0] + (capture[2] - c[1]) * normal[1];
            if (captureSide >= IN_THE_WAY_TOLERANCE) {
                continue; // the capture point is on the team's side of this door
            }
            double distance = Math.hypot(vx, vz);
            if (distance < bestDistance) {
                best = door;
                bestCentre = c;
                bestNormal = normal;
                bestDistance = distance;
            }
        }
        if (best == null) {
            return Optional.empty();
        }
        double reach = 0;
        for (List<int[]> cells : List.of(best.closed(), best.open())) {
            for (int[] cell : cells) {
                double along = (cell[0] + 0.5 - bestCentre[0]) * bestNormal[0] + (cell[2] + 0.5 - bestCentre[1]) * bestNormal[1];
                reach = Math.max(reach, along);
            }
        }
        double out = reach + STAND_OFF;
        int bottom = Integer.MAX_VALUE;
        for (int[] cell : best.closed()) {
            bottom = Math.min(bottom, cell[1]);
        }
        return Optional.of(new Approach(best.name(), bestCentre[0] + bestNormal[0] * out, bottom,
            bestCentre[1] + bestNormal[1] * out));
    }

    /** Plan-view centre {x, z} of the blocks (block centres). */
    private static double[] centre(List<int[]> cells) {
        double x = 0;
        double z = 0;
        for (int[] cell : cells) {
            x += cell[0] + 0.5;
            z += cell[2] + 0.5;
        }
        return new double[] {x / cells.size(), z / cells.size()};
    }

    private static double[] nearest(List<double[]> points, double[] c) {
        double[] best = points.get(0);
        double bestDistance = Double.POSITIVE_INFINITY;
        for (double[] p : points) {
            double d = Math.hypot(p[0] - c[0], p[1] - c[1]);
            if (d < bestDistance) {
                best = p;
                bestDistance = d;
            }
        }
        return best;
    }

    /**
     * The door plane's horizontal unit normal pointing to {@code (vx, vz)}'s side; null when the side lies in the
     * plane (or on the door).
     */
    private static double[] normalTowards(List<int[]> cells, double[] c, double vx, double vz) {
        double sxx = 0;
        double szz = 0;
        double sxz = 0;
        for (int[] cell : cells) {
            double dx = cell[0] + 0.5 - c[0];
            double dz = cell[2] + 0.5 - c[1];
            sxx += dx * dx;
            szz += dz * dz;
            sxz += dx * dz;
        }
        sxx /= cells.size();
        szz /= cells.size();
        sxz /= cells.size();
        double half = (sxx - szz) / 2;
        double largest = (sxx + szz) / 2 + Math.sqrt(half * half + sxz * sxz);
        double nx = vx;
        double nz = vz;
        if (largest >= 0.1) {
            double angle = 0.5 * Math.atan2(2 * sxz, sxx - szz);
            double ax = Math.cos(angle);
            double az = Math.sin(angle);
            double along = vx * ax + vz * az;
            nx = vx - along * ax;
            nz = vz - along * az;
        }
        double length = Math.hypot(nx, nz);
        if (length < 1e-6) {
            return null;
        }
        return new double[] {nx / length, nz / length};
    }
}

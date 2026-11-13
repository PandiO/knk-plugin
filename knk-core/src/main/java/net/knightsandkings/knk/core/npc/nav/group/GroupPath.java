package net.knightsandkings.knk.core.npc.nav.group;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import net.knightsandkings.knk.core.npc.nav.HopPlanner;
import net.knightsandkings.knk.core.npc.nav.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NavWorld;

/**
 * The path a group walks: floor points in walking order with their distance along the path, the leader's route line
 * where it follows one and its own footsteps elsewhere. Followers walk it at a distance behind the leader and an
 * offset to the side ({@link #point}). Distances along the path are horizontal. What the world says about a path
 * point (its regions, its lanes) is looked up once and kept: the path lives for one group order. Main thread.
 */
final class GroupPath {

    static final class Node {
        final NavPoint floor;
        final double arc;
        private Set<String> regions;
        private double wideFor = -1;
        private boolean wide;
        /** Lane points at this path point by side, looked up in the world once ({@link #NO_LANE}: none). */
        private java.util.Map<Double, NavPoint> lanes;

        Node(NavPoint floor, double arc) {
            this.floor = floor;
            this.arc = arc;
        }
    }

    private final List<Node> nodes = new ArrayList<>();

    int size() {
        return nodes.size();
    }

    boolean isEmpty() {
        return nodes.isEmpty();
    }

    Node get(int index) {
        return nodes.get(index);
    }

    double arc(int index) {
        return nodes.get(index).arc;
    }

    /** The distance along the path of its last point; 0 when empty. */
    double endArc() {
        return nodes.isEmpty() ? 0 : nodes.get(nodes.size() - 1).arc;
    }

    void clear() {
        nodes.clear();
    }

    /** Keeps the first {@code keep} points. */
    void truncate(int keep) {
        while (nodes.size() > Math.max(0, keep)) {
            nodes.remove(nodes.size() - 1);
        }
    }

    /** Appends a floor point as it is (the leader stood there); returns its index. */
    int add(NavPoint floor) {
        double arc = nodes.isEmpty() ? 0 : endArc() + nodes.get(nodes.size() - 1).floor.horizontalDistance(floor);
        nodes.add(new Node(floor, arc));
        return nodes.size() - 1;
    }

    /**
     * Appends a point of a route line, moved onto a floor within reach of the previous point's (floor continuity, as
     * the leader's own waypoints: no jump to the flight above on a staircase); returns its index.
     */
    int addSnapped(NavWorld world, NavPoint linePoint, int fallbackFloorY) {
        int refY = nodes.isEmpty() ? fallbackFloorY : (int) Math.round(nodes.get(nodes.size() - 1).floor.y());
        return add(HopPlanner.snapNear(world, linePoint, refY));
    }

    /** The index of the point nearest a feet position among {@code [from, to)} (height counts fully). */
    int nearest(NavPoint feet, int from, int to) {
        int best = Math.max(0, from);
        double bestD = Double.MAX_VALUE;
        for (int i = Math.max(0, from); i < Math.min(nodes.size(), to); i++) {
            NavPoint p = nodes.get(i).floor;
            double d = sq(p.x() - feet.x()) + sq(p.y() + 1 - feet.y()) + sq(p.z() - feet.z());
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /** The last point at or before {@code arc} along the path (the first point for anything before it). */
    int indexAtArc(double arc) {
        int lo = 0;
        int hi = nodes.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (nodes.get(mid).arc <= arc) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    /** The path's horizontal direction at a point as a unit vector {@code {x, z}}; {@code {1, 0}} when it has none. */
    double[] heading(int index) {
        NavPoint at = nodes.get(index).floor;
        NavPoint before = at;
        for (int i = index - 1; i >= 0 && before.horizontalDistance(at) < 0.5; i--) {
            before = nodes.get(i).floor;
        }
        NavPoint after = at;
        for (int i = index + 1; i < nodes.size() && after.horizontalDistance(at) < 0.5; i++) {
            after = nodes.get(i).floor;
        }
        double dx = after.x() - before.x();
        double dz = after.z() - before.z();
        double len = Math.hypot(dx, dz);
        return len < 1e-6 ? new double[] {1, 0} : new double[] {dx / len, dz / len};
    }

    /** How far a feet position is along the path, given the point nearest it. */
    double arcOf(NavPoint feet, int nearest) {
        NavPoint p = nodes.get(nearest).floor;
        double[] h = heading(nearest);
        double along = (feet.x() - p.x()) * h[0] + (feet.z() - p.z()) * h[1];
        // past the path's ends the distance simply goes on (a slot ahead of a leader that arrived)
        double min = nearest == 0 ? -Double.MAX_VALUE : -1;
        double max = nearest == nodes.size() - 1 ? Double.MAX_VALUE : 1;
        return nodes.get(nearest).arc + Math.max(min, Math.min(max, along));
    }

    Set<String> regions(NavWorld world, int index) {
        Node node = nodes.get(index);
        if (node.regions == null) {
            NavPoint p = node.floor;
            node.regions = world.regionsAt(p.world(), p.blockX(), p.blockY() + 1, p.blockZ());
        }
        return node.regions;
    }

    /**
     * The feet position {@code along} blocks past a path point (in its direction) and {@code side} blocks to the
     * right of it, or null when an NPC cannot walk there as part of this path: no floor within a block of the
     * path's height there or on the way out from the path's centre, a chunk that is not loaded, or other WorldGuard
     * regions than the path's own point (a lane never enters a region the path does not).
     */
    NavPoint point(NavWorld world, int index, double along, double side) {
        if (along != 0 || side == 0) {
            return lookUp(world, index, along, side);
        }
        Node node = nodes.get(index);
        if (node.lanes == null) {
            node.lanes = new java.util.HashMap<>(4);
        }
        NavPoint lane = node.lanes.get(side);
        if (lane == null) {
            lane = lookUp(world, index, 0, side);
            node.lanes.put(side, lane == null ? NO_LANE : lane);
        }
        return lane == NO_LANE ? null : lane;
    }

    private static final NavPoint NO_LANE = new NavPoint("", 0, 0, 0);

    private NavPoint lookUp(NavWorld world, int index, double along, double side) {
        Node node = nodes.get(index);
        NavPoint c = node.floor;
        double[] h = heading(index);
        double cx = c.x() + h[0] * along;
        double cz = c.z() + h[1] * along;
        int y = (int) Math.round(c.y());
        if (along != 0) {
            y = floorNear(world, c.world(), cx, y, cz);
            if (y == Integer.MIN_VALUE) {
                return null;
            }
        }
        if (side == 0) {
            return new NavPoint(c.world(), cx, y + 1, cz);
        }
        // to the right of heading (hx, hz) is (-hz, hx)
        double rx = -h[1];
        double rz = h[0];
        double sign = Math.signum(side);
        double reach = Math.abs(side);
        for (double d = Math.min(1, reach); ; d = Math.min(reach, d + 1)) {
            double x = cx + rx * sign * d;
            double z = cz + rz * sign * d;
            y = floorNear(world, c.world(), x, y, z);
            if (y == Integer.MIN_VALUE) {
                return null;
            }
            if (d >= reach) {
                if (!world.regionsAt(c.world(), (int) Math.floor(x), y + 1, (int) Math.floor(z))
                    .equals(regions(world, index))) {
                    return null;
                }
                return new NavPoint(c.world(), x, y + 1, z);
            }
        }
    }

    /** Whether the path is wide enough at a point for slots {@code halfWidth} blocks to either side. */
    boolean wide(NavWorld world, int index, double halfWidth) {
        Node node = nodes.get(index);
        if (node.wideFor != halfWidth) {
            node.wideFor = halfWidth;
            node.wide = point(world, index, 0, halfWidth) != null && point(world, index, 0, -halfWidth) != null;
        }
        return node.wide;
    }


    /** A standable floor in this block column at {@code y} or one block above or below it; MIN_VALUE without one. */
    private static int floorNear(NavWorld world, String name, double x, int y, double z) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        if (!world.isLoaded(name, bx, bz)) {
            return Integer.MIN_VALUE;
        }
        for (int candidate : new int[] {y, y + 1, y - 1}) {
            if (world.isStandableFloor(name, bx, candidate, bz)) {
                return candidate;
            }
        }
        return Integer.MIN_VALUE;
    }

    private static double sq(double v) {
        return v * v;
    }
}

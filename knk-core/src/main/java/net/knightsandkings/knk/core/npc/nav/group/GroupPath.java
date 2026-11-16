package net.knightsandkings.knk.core.npc.nav.group;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import net.knightsandkings.knk.core.npc.nav.HopPlanner;
import net.knightsandkings.knk.core.npc.nav.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NavWorld;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics;

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
        /**
         * Per width of the group's formation ({@link #levels}): the shift of its lanes off the path's centre that
         * lets it fit here; NaN not looked at yet, {@link Double#POSITIVE_INFINITY} it does not fit (4b.3 item 6).
         */
        private double[] shifts;
        /** The widest width that fits here (an index into {@link #levels}); -1 not looked at yet. */
        private int widest = -1;
        /** Elastic (link M3): the free width across the path here, looked at once; null not yet. */
        private Fit fit;
        /** Elastic: the widest width that fits here squeezed (an index into {@link #levels}); -1 not looked at yet. */
        private int elasticWidest = -1;

        Node(NavPoint floor, double arc) {
            this.floor = floor;
            this.arc = arc;
        }
    }

    private final List<Node> nodes = new ArrayList<>();
    /** The half widths of the group's formation widths, widest first, the column (0) last (4b.3 item 3). */
    private double[] levels = {0};

    int size() {
        return nodes.size();
    }

    /** The path's floor points, as a live read-only view (for {@link net.knightsandkings.knk.core.npc.nav.PathTargets}). */
    List<NavPoint> floors() {
        return new java.util.AbstractList<>() {
            @Override
            public NavPoint get(int index) {
                return nodes.get(index).floor;
            }

            @Override
            public int size() {
                return nodes.size();
            }
        };
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
     * the leader's own waypoints: no jump to the flight above on a staircase); returns its index. A line point over
     * a drop moves onto the walk beside it ({@link HopPlanner#surfaceNear}); with no walk beside it the previous
     * point is repeated, so the path keeps one point per line point and never drops to the ground below a ledge
     * (PandiO/knk-workspace#592).
     */
    int addSnapped(NavWorld world, NavPoint linePoint, int fallbackFloorY) {
        int refY = nodes.isEmpty() ? fallbackFloorY : (int) Math.round(nodes.get(nodes.size() - 1).floor.y());
        NavPoint floor = HopPlanner.surfaceNear(world, linePoint, refY);
        if (floor == null) {
            floor = nodes.isEmpty() ? HopPlanner.snapNear(world, linePoint, refY) : nodes.get(nodes.size() - 1).floor;
        }
        return add(floor);
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
     * path's height there, {@link #LANE_CLEARANCE} to either side of it across the path, or on the way out from the
     * path's centre, a chunk that is not loaded, or other WorldGuard regions than the path's own point (a lane never
     * enters a region the path does not).
     */
    NavPoint point(NavWorld world, int index, double along, double side) {
        if (Math.abs(side) < 1e-9) {
            side = 0; // a shifted lane can land on the centre: one key for it
        }
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
                // a body, not a line: a lane exactly on a block edge (x 26.0 in a 1-wide passage at x 26) has a floor
                // under its point but a wall at its shoulder (found by npc-corners-walk)
                for (double edge : new double[] {LANE_CLEARANCE, -LANE_CLEARANCE}) {
                    if (floorNear(world, c.world(), x + rx * edge, y, z + rz * edge) == Integer.MIN_VALUE) {
                        return null;
                    }
                }
                return new NavPoint(c.world(), x, y + 1, z);
            }
        }
    }

    /**
     * The half widths of the formation's widths, widest first, ending with the column's 0 (implementation plan 4b.3
     * item 3). Changing them forgets what was worked out per point.
     */
    void levels(double[] halfWidths) {
        if (java.util.Arrays.equals(levels, halfWidths)) {
            return;
        }
        levels = halfWidths.clone();
        for (Node node : nodes) {
            node.shifts = null;
            node.widest = -1;
            node.elasticWidest = -1;
        }
    }

    int levelCount() {
        return levels.length;
    }

    /**
     * The widest of the formation's widths that fits at a path point (an index into {@link #levels}; the column,
     * the last, always fits, and is the only one on a flight of stairs), worked out once per point (implementation plan 4b.3 item 6): the tick cost of a
     * window scan stays one cached look per point.
     */
    int widest(NavWorld world, int index) {
        Node node = nodes.get(index);
        if (node.widest < 0 && stair(index)) {
            node.widest = levels.length - 1; // a flight of stairs is walked as a column (4b.3 item 3), however wide
        }
        if (node.widest < 0) {
            int level = 0;
            while (level < levels.length - 1 && Double.isNaN(shift(world, index, level))) {
                level++;
            }
            node.widest = level;
        }
        return node.widest;
    }

    /** Whether the path climbs or descends a block or more at this point (from the point before or to the next). */
    boolean stair(int index) {
        double y = nodes.get(index).floor.y();
        return (index > 0 && Math.abs(nodes.get(index - 1).floor.y() - y) >= 1)
            || (index + 1 < nodes.size() && Math.abs(nodes.get(index + 1).floor.y() - y) >= 1);
    }

    /**
     * Where the lanes of width {@code level} lie at a path point: the shift of the formation's centre off the path's
     * centre (to the right, in blocks) that lets its outermost lanes stand there, or NaN when the width does not fit
     * (implementation plan 4b.3 item 4, today's lane rule). The path's own centre is tried first, then shifts of half
     * a block to either side up to the width's half width (at least half a block), so 2 abreast fit a 2-wide passage
     * whose route line runs down one of its two columns, and 3 abreast a 3-wide road whose line hugs a wall. Looked
     * up once per point and width.
     */
    double shift(NavWorld world, int index, int level) {
        double half = levels[Math.max(0, Math.min(level, levels.length - 1))];
        if (half <= 0.01) {
            return 0;
        }
        Node node = nodes.get(index);
        if (node.shifts == null) {
            node.shifts = new double[levels.length];
            java.util.Arrays.fill(node.shifts, Double.NaN);
        }
        double known = node.shifts[level];
        if (Double.isNaN(known)) {
            known = Double.POSITIVE_INFINITY;
            double most = Math.max(0.5, half);
            for (double step = 0; step <= most + 1e-9 && known == Double.POSITIVE_INFINITY; step += 0.5) {
                for (double shift : step == 0 ? new double[] {0} : new double[] {step, -step}) {
                    if (clear(world, index, shift + half) && clear(world, index, shift - half)) {
                        known = shift;
                        break;
                    }
                }
            }
            node.shifts[level] = known;
        }
        return known == Double.POSITIVE_INFINITY ? Double.NaN : known;
    }

    /**
     * Why width {@code level} does not fit at a path point (implementation plan 4c.3 decision 2.8, counting only):
     * {@code STAIR} where {@link #stair} makes the point a column; otherwise the lanes are tried as {@link #shift}
     * tries them, each try judged by its worse side (no floor on the way out or at the lane point: {@code NARROW};
     * floor but other regions than the path's point: {@code RULES}; floor and regions but no
     * {@link #LANE_CLEARANCE} at its shoulder: {@code EDGE}), and the try that came nearest to fitting names the
     * reason ({@code EDGE} before {@code RULES} before {@code NARROW}). {@code DOOR} is the group's own call (it
     * knows the gate doors). Reads the same blocks a lane look-up reads; only called when a width changes.
     */
    NpcNavigationMetrics.FoldReason misfitReason(NavWorld world, int index, int level) {
        if (stair(index)) {
            return NpcNavigationMetrics.FoldReason.STAIR;
        }
        double half = levels[Math.max(0, Math.min(level, levels.length - 1))];
        NpcNavigationMetrics.FoldReason best = NpcNavigationMetrics.FoldReason.NARROW;
        double most = Math.max(0.5, half);
        for (double step = 0; step <= most + 1e-9; step += 0.5) {
            for (double shift : step == 0 ? new double[] {0} : new double[] {step, -step}) {
                NpcNavigationMetrics.FoldReason right = laneReason(world, index, shift + half);
                NpcNavigationMetrics.FoldReason left = laneReason(world, index, shift - half);
                NpcNavigationMetrics.FoldReason worse = severity(right) >= severity(left) ? right : left;
                if (worse != null && severity(worse) < severity(best)) {
                    best = worse;
                }
            }
        }
        return best;
    }

    private static int severity(NpcNavigationMetrics.FoldReason reason) {
        if (reason == null) {
            return 0;
        }
        return switch (reason) {
            case EDGE -> 1;
            case RULES -> 2;
            default -> 3;
        };
    }

    /** Why the lane {@code side} blocks beside a path point does not exist (see {@link #lookUp}); null when it does. */
    private NpcNavigationMetrics.FoldReason laneReason(NavWorld world, int index, double side) {
        if (Math.abs(side) < 1e-9) {
            return null; // the path's own centre
        }
        Node node = nodes.get(index);
        NavPoint c = node.floor;
        double[] h = heading(index);
        int y = (int) Math.round(c.y());
        double rx = -h[1];
        double rz = h[0];
        double sign = Math.signum(side);
        double reach = Math.abs(side);
        for (double d = Math.min(1, reach); ; d = Math.min(reach, d + 1)) {
            double x = c.x() + rx * sign * d;
            double z = c.z() + rz * sign * d;
            y = floorNear(world, c.world(), x, y, z);
            if (y == Integer.MIN_VALUE) {
                return NpcNavigationMetrics.FoldReason.NARROW;
            }
            if (d >= reach) {
                if (!world.regionsAt(c.world(), (int) Math.floor(x), y + 1, (int) Math.floor(z))
                    .equals(regions(world, index))) {
                    return NpcNavigationMetrics.FoldReason.RULES;
                }
                for (double edge : new double[] {LANE_CLEARANCE, -LANE_CLEARANCE}) {
                    if (floorNear(world, c.world(), x + rx * edge, y, z + rz * edge) == Integer.MIN_VALUE) {
                        return NpcNavigationMetrics.FoldReason.EDGE;
                    }
                }
                return null;
            }
        }
    }

    /** How far a lane point keeps from a block edge without a floor, across the path (a body, not a line). */
    static final double LANE_CLEARANCE = 0.2;

    /** Whether a lane exists {@code side} blocks beside a path point (its point keeps the clearance, see {@link #point}). */
    private boolean clear(NavWorld world, int index, double side) {
        return point(world, index, 0, side) != null;
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


    // ==================== elastic formations (implementation plan 4c.3 decision 2, link M3) ====================

    /** Why the free width across the path ends on one side (the fold reason of decision 2.8, judged from it). */
    enum Stop {
        /** A face that stops a body, no floor with headroom, or an unloaded chunk. */
        WALL,
        /** No floor within a step and nothing in the way: the walk ends at a drop. */
        DROP,
        /** WorldGuard regions the group's access rules do not let it enter. */
        RULES,
        /** On a flight of stairs: a floor at another height than the path's step (no step of its own for a lane). */
        STEP,
        /** Free as far as it is looked at ({@link #FIT_REACH}). */
        OPEN
    }

    /**
     * The free width across the path at a point (link M3, plan 4c.3 decision 2.1): how far a body's centre can move to
     * the left and to the right of the path's centre, square to its heading, staying on floor with floor continuity
     * (on a flight of stairs at the path's own step height: each lane has its own steps, decision 2.5) and in regions
     * the group may enter (decision 2.6), to the exact block face, each side at most {@link #FIT_REACH}.
     */
    record Fit(double left, double right, Stop leftStop, Stop rightStop) {
        double total() {
            return left + right;
        }

        /** The stop of the nearer side: what limits the width most. */
        Stop limit() {
            return left <= right ? leftStop : rightStop;
        }
    }

    /** The group's access verdict for a lane: may a follower on the path's regions stand in {@code lane}'s? */
    @FunctionalInterface
    interface LaneRules {
        boolean allows(String world, Set<String> path, Set<String> lane);
    }

    /** The most blocks looked at to either side of the path ({@code corridor-half-width}'s decided 4). */
    static final double FIT_REACH = 4.0;
    /** The hard least distance of a lane's centre from a wall or drop (plan 4c.6 question 4: 0.35). */
    static final double HARD_MARGIN = 0.35;

    /** Forgets the elastic fits, for example after a rule change (the access verdicts change). */
    void forgetFits() {
        for (Node node : nodes) {
            node.fit = null;
            node.elasticWidest = -1;
        }
    }

    /** The free width across the path at a point, looked up once (see {@link Fit}). */
    Fit fit(NavWorld world, int index, LaneRules rules) {
        Node node = nodes.get(index);
        if (node.fit == null) {
            NavPoint c = node.floor;
            int y = (int) Math.round(c.y());
            if (!world.isLoaded(c.world(), c.blockX(), c.blockZ())
                || !world.isStandableFloor(c.world(), c.blockX(), y, c.blockZ())) {
                node.fit = new Fit(0, 0, Stop.WALL, Stop.WALL);
            } else {
                double[] h = heading(index);
                boolean stair = stair(index);
                Set<String> regions = regions(world, index);
                // right of heading (hx, hz) is (-hz, hx), as everywhere in the group's lanes
                double[] right = across(world, c, y, -h[1], h[0], stair, regions, rules);
                double[] left = across(world, c, y, h[1], -h[0], stair, regions, rules);
                node.fit = new Fit(left[0], right[0], Stop.values()[(int) left[1]], Stop.values()[(int) right[1]]);
            }
        }
        return node.fit;
    }

    /** How far from the path's centre along a unit direction a lane can go, and why it ends: {distance, stop ordinal}. */
    private double[] across(NavWorld world, NavPoint c, int floorY, double dx, double dz, boolean stair,
                            Set<String> regions, LaneRules rules) {
        int cx = c.blockX();
        int cz = c.blockZ();
        int stepX = dx > 1e-12 ? 1 : dx < -1e-12 ? -1 : 0;
        int stepZ = dz > 1e-12 ? 1 : dz < -1e-12 ? -1 : 0;
        double tMaxX = stepX == 0 ? Double.MAX_VALUE : (stepX > 0 ? cx + 1 - c.x() : c.x() - cx) / Math.abs(dx);
        double tMaxZ = stepZ == 0 ? Double.MAX_VALUE : (stepZ > 0 ? cz + 1 - c.z() : c.z() - cz) / Math.abs(dz);
        double tDeltaX = stepX == 0 ? Double.MAX_VALUE : 1 / Math.abs(dx);
        double tDeltaZ = stepZ == 0 ? Double.MAX_VALUE : 1 / Math.abs(dz);
        int y = floorY;
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
            if (t >= FIT_REACH) {
                return new double[] {FIT_REACH, Stop.OPEN.ordinal()};
            }
            if (!world.isLoaded(c.world(), cx, cz)) {
                return new double[] {t, Stop.WALL.ordinal()};
            }
            int found = Integer.MIN_VALUE;
            for (int candidate : stair ? new int[] {floorY, floorY + 1, floorY - 1} : new int[] {y, y + 1, y - 1}) {
                if (world.isStandableFloor(c.world(), cx, candidate, cz)
                    && world.freeHeight(c.world(), cx, candidate, cz) >= net.knightsandkings.knk.core.npc.nav.NavGeometry.BODY_HEIGHT) {
                    found = candidate;
                    break;
                }
            }
            if (found == Integer.MIN_VALUE) {
                boolean wall = net.knightsandkings.knk.core.npc.nav.NavGeometry.wallAt(world, c.world(), cx, y + 1, cz);
                return new double[] {t, (wall ? Stop.WALL : Stop.DROP).ordinal()};
            }
            if (stair && found != floorY) {
                return new double[] {t, Stop.STEP.ordinal()}; // a lane on a flight keeps to the path's own step
            }
            Set<String> there = world.regionsAt(c.world(), cx, found + 1, cz);
            if (!there.equals(regions) && !rules.allows(c.world(), regions, there)) {
                return new double[] {t, Stop.RULES.ordinal()};
            }
            y = found;
        }
    }

    /**
     * The widest of the formation's widths that fits at a path point squeezed (link M3, decision 2.2): {@code n}
     * abreast at the least lateral spacing need {@code 2 × half × min / spacing + 2 × HARD_MARGIN} of free width
     * ({@code halfUnits[l]} is width {@code l}'s half width in lateral spacings). The column, the last, always fits.
     */
    int elasticWidest(NavWorld world, int index, LaneRules rules, double[] halfUnits, double lateralMin) {
        Node node = nodes.get(index);
        if (node.elasticWidest < 0) {
            double free = fit(world, index, rules).total();
            int level = 0;
            while (level < halfUnits.length - 1 && 2 * halfUnits[level] * lateralMin + 2 * HARD_MARGIN > free + 1e-9) {
                level++;
            }
            node.elasticWidest = level;
        }
        return node.elasticWidest;
    }

    /**
     * The squeezed lateral spacing of width {@code halfUnits} at a fit: as wide as {@code spacing} where the free width
     * allows, down to {@code lateralMin}; {@code lateralMin} where even that does not fit (a merge).
     */
    static double squeezed(Fit fit, double halfUnits, double spacing, double lateralMin) {
        if (halfUnits <= 1e-9) {
            return spacing;
        }
        double room = (fit.total() - 2 * HARD_MARGIN) / (2 * halfUnits);
        return Math.max(lateralMin, Math.min(spacing, room));
    }

    /**
     * Where the centre of a formation of half width {@code half} (blocks) lies at a fit, off the path's centre to the
     * right: the path's centre where the outer lanes keep {@code margin} to the faces, else as near it as they do; the
     * margin is {@code wallClearance} where the road allows and never less than {@link #HARD_MARGIN}. Where even that
     * does not fit, the middle of the free width.
     */
    static double centre(Fit fit, double half, double wallClearance) {
        double spare = (fit.total() - 2 * half) / 2;
        if (spare < HARD_MARGIN) {
            return (fit.right() - fit.left()) / 2;
        }
        double margin = Math.max(HARD_MARGIN, Math.min(wallClearance, spare));
        double lo = -fit.left() + margin + half;
        double hi = fit.right() - margin - half;
        return Math.max(lo, Math.min(hi, 0));
    }

    /**
     * A lane's feet position {@code lateral} blocks to the right of a path point and {@code along} blocks past it, on
     * the floor there (the path's height first, then a block up or down); null when there is none.
     */
    NavPoint lateralPoint(NavWorld world, int index, double along, double lateral) {
        Node node = nodes.get(index);
        NavPoint c = node.floor;
        double[] h = heading(index);
        double x = c.x() + h[0] * along - h[1] * lateral;
        double z = c.z() + h[1] * along + h[0] * lateral;
        int y = floorNear(world, c.world(), x, (int) Math.round(c.y()), z);
        return y == Integer.MIN_VALUE ? null : new NavPoint(c.world(), x, y + 1, z);
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

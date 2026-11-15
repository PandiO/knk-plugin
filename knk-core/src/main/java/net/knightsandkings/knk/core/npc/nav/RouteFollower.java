package net.knightsandkings.knk.core.npc.nav;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.knightsandkings.knk.core.npc.nav.corridor.CorridorShaper;

/**
 * One NPC's progress along a K&K route (BEHAVIOR_MODEL NAV-14, NAV-15; decision Q8): the road line as floor points
 * every {@link #STEP} blocks plus its corners, handed to the mover one stretch at a time as feet waypoints with floor
 * continuity between them, the next stretch prepared before the current one ends. It also answers the progress
 * watchdog (NAV-17): how far along the line the NPC has come, and where a recovery leg should aim. Only the stretch
 * ahead is looked at in the world; the line itself needs no chunks. Main thread; one instance per route.
 */
public final class RouteFollower {

    /** Spacing of the waypoints along the route. */
    public static final double STEP = 1.5;
    /** A recovery leg aims at most this many blocks along the line ahead of the NPC unless told otherwise. */
    public static final double RECOVERY_REACH = 16;

    public enum Kind {
        /** Waypoints to follow. */
        WAYPOINTS,
        /** The stretch reaches a chunk that is not loaded: nothing was handed out. */
        UNLOADED,
        /** Nothing left of the line (or the rest collapsed onto the last waypoint). */
        DONE
    }

    /**
     * @param waypoints   feet positions (at block centres unless corridor steering shaped them), for
     *                    {@link Kind#WAYPOINTS}
     * @param chokepoints chokepoints corridor steering aligned on this stretch (0 without it); the mover then walks it
     *                    with {@link CorridorShaper#CHOKE_MARGIN} (plan 4c.3 decision 1.4)
     */
    public record Stretch(Kind kind, List<NavPoint> waypoints, int chokepoints) {
        public Stretch(Kind kind, List<NavPoint> waypoints) {
            this(kind, waypoints, 0);
        }
    }

    /**
     * Corridor steering for a stretch (implementation plan 4c.3 decision 1, link M2; key {@code corridor-steering}):
     * the shared shaper and the decided values.
     */
    public record Steering(CorridorShaper shaper, CorridorShaper.Options options) {
        public Steering {
            Objects.requireNonNull(shaper, "shaper");
            Objects.requireNonNull(options, "options");
        }
    }

    private final List<NavPoint> line;
    /** Blocks along the line at each point. */
    private final double[] along;
    private final CornerClearance corners;
    /** Where the next stretch starts. */
    private int next;
    /** The stretch last handed out: [sentStart, sentEnd). */
    private int sentStart;
    private int sentEnd;
    private int lastSnapY = Integer.MIN_VALUE;
    private int lastSnapEnd = -1;
    private int bestIndex = -1;
    private List<NavPoint> lastSent = List.of();
    /** The last waypoint the walk was given (a stretch's last, or a recovery target), the next stretch's anchor. */
    private NavPoint lastWaypoint;

    /** @param roadLine the route as floor points (the road's polyline), at least one */
    public RouteFollower(List<NavPoint> roadLine) {
        Objects.requireNonNull(roadLine, "roadLine");
        if (roadLine.isEmpty()) {
            throw new IllegalArgumentException("a route needs at least one point");
        }
        this.line = List.copyOf(HopPlanner.resampleKeepingCorners(roadLine, STEP));
        this.along = new double[line.size()];
        for (int i = 1; i < line.size(); i++) {
            NavPoint a = line.get(i - 1);
            NavPoint b = line.get(i);
            along[i] = along[i - 1] + Math.sqrt(sq(b.x() - a.x()) + sq(b.y() - a.y()) + sq(b.z() - a.z()));
        }
        this.corners = new CornerClearance(line);
    }

    /** The corner clearance of this line (its turns). */
    public CornerClearance corners() {
        return corners;
    }

    /**
     * How far along the line, in blocks, an NPC with its feet here is: the nearest point's distance plus the NPC's
     * projection onto the segment after or before it (implementation plan 4b.2 item 7.1: a recovery step has worked
     * when the NPC came at least a block further along).
     */
    public double alongAt(NavPoint feet) {
        int i = nearestIndex(feet);
        double base = along[i];
        NavPoint p = line.get(i);
        if (i + 1 < line.size()) {
            double t = project(p, line.get(i + 1), feet);
            if (t > 0) {
                return base + t;
            }
        }
        if (i > 0) {
            double t = project(p, line.get(i - 1), feet);
            if (t > 0) {
                return base - t;
            }
        }
        return base;
    }

    /** Distance along a→b of the projection of {@code feet}, clamped to [0, |ab|] (horizontal). */
    private static double project(NavPoint a, NavPoint b, NavPoint feet) {
        double dx = b.x() - a.x();
        double dz = b.z() - a.z();
        double len = Math.hypot(dx, dz);
        if (len < 1e-9) {
            return 0;
        }
        double t = ((feet.x() - a.x()) * dx + (feet.z() - a.z()) * dz) / len;
        return Math.max(0, Math.min(len, t));
    }

    /**
     * Hands the line out again from the point nearest the NPC (a recovery step walks the rest of the stretch again
     * from where it ended); the next stretch's floors continue from the NPC's own floor.
     */
    public void restartAtNpc(NavPoint feet) {
        int i = nearestIndex(feet);
        next = i;
        sentStart = sentEnd = i;
        lastSnapEnd = -1;
        lastWaypoint = null;
    }

    /** The route as floor points every {@link #STEP} blocks, corners included. */
    public List<NavPoint> line() {
        return line;
    }

    /** Whether a stretch is still to be handed out. */
    public boolean hasMore() {
        return next < line.size();
    }

    /** The last floor point of the stretch last handed out, or null before the first. */
    public NavPoint sentEndPoint() {
        return sentEnd <= 0 ? null : line.get(Math.min(line.size(), sentEnd) - 1);
    }

    /** The index in {@link #line} where the next stretch starts. */
    public int nextIndex() {
        return next;
    }

    /** The index of the line point nearest a floor point, over the whole line (for placing gates on the route). */
    public int indexNearest(NavPoint floorPoint) {
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < line.size(); i++) {
            NavPoint p = line.get(i);
            double d = sq(p.x() - floorPoint.x()) + sq(p.y() - floorPoint.y()) + sq(p.z() - floorPoint.z());
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /** The waypoints last handed out (diagnostics). */
    public List<NavPoint> lastSent() {
        return lastSent;
    }

    public int bestIndex() {
        return bestIndex;
    }

    /**
     * The next stretch of about {@code stretchBlocks} blocks as feet waypoints. Each waypoint's floor is within
     * {@link HopPlanner#CONTINUITY_RANGE} blocks of the previous one; the first one continues from the previous
     * stretch's last floor, or from {@code npcFloorY} (the floor the NPC stands on) when this stretch does not follow
     * straight on. {@link Kind#UNLOADED} hands nothing out and changes nothing, so the caller can ask again later.
     */
    public Stretch next(NavWorld world, double stretchBlocks, int npcFloorY) {
        return next(world, stretchBlocks, npcFloorY, 0);
    }

    /**
     * As {@link #next(NavWorld, double, int)}, with {@code cornerClearance} blocks kept from walls on the inside of
     * the line's turns ({@link CornerClearance}; 0 = off).
     */
    public Stretch next(NavWorld world, double stretchBlocks, int npcFloorY, double cornerClearance) {
        return next(world, stretchBlocks, npcFloorY, cornerClearance, null);
    }

    /**
     * As {@link #next(NavWorld, double, int, double)}, with the stretch shaped in its corridor first when
     * {@code steering} is given (implementation plan 4c.3 decision 1; null = off, today's line). Corner clearance and
     * the waypoint chain still run on the shaped line (decision 4).
     */
    public Stretch next(NavWorld world, double stretchBlocks, int npcFloorY, double cornerClearance,
                        Steering steering) {
        if (!hasMore()) {
            return new Stretch(Kind.DONE, List.of());
        }
        int start = next;
        int perStretch = Math.max(4, (int) Math.round(stretchBlocks / STEP));
        int end = Math.min(line.size(), start + perStretch);
        for (NavPoint p : line.subList(start, end)) {
            if (!world.isLoaded(p.world(), p.blockX(), p.blockZ())) {
                return new Stretch(Kind.UNLOADED, List.of());
            }
        }
        List<NavPoint> points = new ArrayList<>();
        List<Integer> indexes = new ArrayList<>();
        int lastX = Integer.MIN_VALUE;
        int lastY = Integer.MIN_VALUE;
        int lastZ = Integer.MIN_VALUE;
        boolean straightOn = start == lastSnapEnd && lastSnapY != Integer.MIN_VALUE;
        int refY = straightOn ? lastSnapY : npcFloorY;
        for (int i = start; i < end; i++) {
            NavPoint p = line.get(i);
            NavPoint floor = HopPlanner.surfaceNear(world, p, refY);
            if (floor == null) {
                if (i < end - 1 || !points.isEmpty()) {
                    continue; // over a drop with no walk beside it (#592): the chain below joins its neighbours
                }
                floor = HopPlanner.snapNear(world, p, refY); // nothing else in this stretch: as before
            }
            refY = (int) Math.round(floor.y());
            int bx = floor.blockX();
            int bz = floor.blockZ();
            if (bx == lastX && refY == lastY && bz == lastZ) {
                continue;
            }
            points.add(new NavPoint(p.world(), bx + 0.5, refY + 1, bz + 0.5));
            indexes.add(i);
            lastX = bx;
            lastY = refY;
            lastZ = bz;
        }
        lastSnapY = refY;
        lastSnapEnd = end;
        sentStart = start;
        sentEnd = end;
        next = end;
        if (points.isEmpty()) {
            return new Stretch(Kind.DONE, List.of());
        }
        int chokepoints = 0;
        if (steering != null && points.size() >= 2) {
            CorridorShaper.ShapedLine shaped = steering.shaper().shape(world, shapingInput(points, indexes), indexes,
                steering.options(), end == line.size());
            points = new ArrayList<>(shaped.waypoints());
            indexes = new ArrayList<>(shaped.lineIndexes());
            chokepoints = shaped.chokepoints();
        }
        if (cornerClearance > 0) {
            points = corners.apply(world, points, indexes, cornerClearance);
        }
        points = WaypointChain.bridged(world, straightOn ? lastWaypoint : null, points);
        lastSent = List.copyOf(points);
        lastWaypoint = points.get(points.size() - 1);
        return new Stretch(Kind.WAYPOINTS, lastSent, chokepoints);
    }

    /**
     * The waypoints as the corridor shaper starts from: the road line's own position where it lies in the waypoint's
     * block (not the block centre, so the line is not made to wobble), else the block centre.
     */
    private List<NavPoint> shapingInput(List<NavPoint> points, List<Integer> indexes) {
        List<NavPoint> out = new ArrayList<>(points.size());
        for (int k = 0; k < points.size(); k++) {
            NavPoint p = points.get(k);
            NavPoint raw = line.get(indexes.get(k));
            out.add(raw.blockX() == p.blockX() && raw.blockZ() == p.blockZ()
                ? new NavPoint(p.world(), raw.x(), p.y(), raw.z()) : p);
        }
        return out;
    }

    /**
     * The index of the line point nearest the NPC's feet, searched around the stretch in hand (height counts half, so
     * the flight above or below on a staircase is not taken for the NPC's own).
     */
    public int nearestIndex(NavPoint feet) {
        int from = Math.max(0, Math.min(sentStart, bestIndex < 0 ? sentStart : bestIndex) - 4);
        int to = Math.min(line.size(), Math.max(sentEnd, sentStart) + 6);
        int best = from;
        double bestD = Double.MAX_VALUE;
        for (int i = from; i < to; i++) {
            NavPoint p = line.get(i);
            double d = sq(p.x() - feet.x()) + sq(p.y() + 1 - feet.y()) * 0.5 + sq(p.z() - feet.z());
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /**
     * The watchdog's question: has the NPC come further <b>along</b> the route than ever before? Moving is not
     * progress: an NPC jumping in circles moves a lot. Remembers the furthest point reached.
     */
    public boolean progressed(NavPoint feet) {
        int nearest = nearestIndex(feet);
        if (nearest > bestIndex) {
            bestIndex = nearest;
            return true;
        }
        return false;
    }

    /**
     * Stuck on the line, the local leg ahead (implementation plan 4b.2 items 5 and 6.3): the first point at least
     * {@link PathTargets#MIN_AHEAD} blocks along the line from the NPC's place on it (height counted), on the NPC's own
     * floor, at most {@link #RECOVERY_REACH} blocks along; the next stretch starts right after it. Null (and nothing
     * changed) when there is none.
     */
    public NavPoint recoveryTarget(NavWorld world, NavPoint feet) {
        return recoveryTarget(world, feet, RECOVERY_REACH, null);
    }

    /**
     * As {@link #recoveryTarget(NavWorld, NavPoint)}, at most {@code maxAlong} blocks along the line, only where
     * {@code mayStand} lets the NPC stand (null: anywhere).
     */
    public NavPoint recoveryTarget(NavWorld world, NavPoint feet, double maxAlong,
                                   java.util.function.Predicate<NavPoint> mayStand) {
        PathTargets.Target target = PathTargets.ahead(world, line, nearestIndex(feet), line.size() - 1,
            feet.blockY() - 1, maxAlong, mayStand);
        if (target == null) {
            return null;
        }
        next = target.index() + 1;
        sentStart = sentEnd = next;
        lastSnapY = target.floor().blockY();
        lastSnapEnd = next;
        lastWaypoint = HopPlanner.feet(target.floor());
        bestIndex = Math.max(bestIndex, target.index());
        return target.floor();
    }

    private static double sq(double v) {
        return v * v;
    }
}

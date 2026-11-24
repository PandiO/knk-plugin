package net.knightsandkings.knk.core.npc.nav;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.knightsandkings.knk.core.navigation.shape.CorridorShaper;
import net.knightsandkings.knk.core.navigation.shape.NavGeometry;
import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.navigation.shape.NavWorld;
import net.knightsandkings.knk.core.navigation.shape.PlazaCrossing;

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
    /** A plaza junction further than this from the route line is not on it (link M5). */
    public static final double PLAZA_ON_LINE = 2.0;
    /** A recovery leg aims at most this many blocks along the line ahead of the NPC unless told otherwise. */
    public static final double RECOVERY_REACH = 16;
    /**
     * Steps per block of the grid the line's x and z are put on (PandiO/knk-workspace#958): a multiple of
     * {@code 1 / LINE_GRID} is exact as a double anywhere within the world border, so the line has the same shape
     * wherever the route lies. Without it a coordinate's last bits depend on its magnitude (a double has half the
     * precision at x 32768 and above that it has below), and a line point on a block face (x exactly 1412.0) came
     * out a hair on one side of the face or the other, which changed the stretch the corridor shaper made.
     */
    public static final double LINE_GRID = 4096;

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
    /**
     * Per line point: whether it is a cell of a walk the walk search planned (link C1, PandiO/knk-workspace#700); null
     * when none is. Such points are handed out as they are: no corridor shaping, no corner clearance, their own floor.
     */
    private final boolean[] walked;
    /** The junctions the line passes that may be plazas (link M5), each at its line point, in route order. */
    private final List<PlazaOnLine> plazas;
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

    /**
     * A junction on the line that may be a plaza (link M5).
     *
     * @param index    the line point at the junction
     * @param crossing the crossing, its centre moved onto that line point
     */
    public record PlazaOnLine(int index, PlazaCrossing crossing) {
    }

    /** @param roadLine the route as floor points (the road's polyline), at least one */
    public RouteFollower(List<NavPoint> roadLine) {
        this(roadLine, List.of());
    }

    /**
     * @param roadLine the route as floor points (the road's polyline), at least one
     * @param plazas   the junctions the route passes that may be plazas (link M5; a junction further than
     *                 {@link #PLAZA_ON_LINE} blocks from the line is left out)
     */
    public RouteFollower(List<NavPoint> roadLine, List<PlazaCrossing> plazas) {
        this(roadLine, plazas, null);
    }

    /**
     * As {@link #RouteFollower(List, List)}, with the points of {@code roadLine} that are cells of a walk the walk search
     * planned (link C1): {@code walkedPoints[i]} for {@code roadLine.get(i)}; null for none. A point added between two
     * walked points is walked too.
     */
    public RouteFollower(List<NavPoint> roadLine, List<PlazaCrossing> plazas, boolean[] walkedPoints) {
        Objects.requireNonNull(roadLine, "roadLine");
        if (roadLine.isEmpty()) {
            throw new IllegalArgumentException("a route needs at least one point");
        }
        if (walkedPoints != null && walkedPoints.length != roadLine.size()) {
            throw new IllegalArgumentException("one walked flag per line point");
        }
        roadLine = onGrid(roadLine);
        if (walkedPoints == null) {
            this.line = onGrid(HopPlanner.resampleKeepingCorners(roadLine, STEP));
            this.walked = null;
        } else {
            List<NavPoint> points = new ArrayList<>();
            List<Boolean> flags = new ArrayList<>();
            points.add(roadLine.get(0));
            flags.add(walkedPoints[0]);
            for (int i = 1; i < roadLine.size(); i++) {
                List<NavPoint> piece = HopPlanner.resample(List.of(roadLine.get(i - 1), roadLine.get(i)), STEP);
                for (int k = 0; k < piece.size(); k++) {
                    NavPoint q = piece.get(k);
                    NavPoint last = points.get(points.size() - 1);
                    if (Math.abs(last.x() - q.x()) < 1e-6 && Math.abs(last.y() - q.y()) < 1e-6
                        && Math.abs(last.z() - q.z()) < 1e-6) {
                        continue;
                    }
                    points.add(q);
                    flags.add(k == piece.size() - 1 ? walkedPoints[i] : walkedPoints[i - 1] && walkedPoints[i]);
                }
            }
            this.line = onGrid(points);
            this.walked = new boolean[flags.size()];
            for (int i = 0; i < flags.size(); i++) {
                walked[i] = flags.get(i);
            }
        }
        List<PlazaOnLine> onLine = new ArrayList<>();
        for (PlazaCrossing p : plazas == null ? List.<PlazaCrossing>of() : plazas) {
            int best = -1;
            double bestD = Double.MAX_VALUE;
            for (int i = 0; i < line.size(); i++) {
                NavPoint q = line.get(i);
                double d = q.horizontalDistance(p.centre());
                if (q.world().equals(p.centre().world()) && Math.abs(q.y() - p.centre().y()) <= 3 && d < bestD) {
                    bestD = d;
                    best = i;
                }
            }
            if (best >= 0 && bestD <= PLAZA_ON_LINE) {
                onLine.add(new PlazaOnLine(best, new PlazaCrossing(line.get(best), p.radius())));
            }
        }
        onLine.sort(java.util.Comparator.comparingInt(PlazaOnLine::index));
        this.plazas = List.copyOf(onLine);
        this.along = new double[line.size()];
        for (int i = 1; i < line.size(); i++) {
            NavPoint a = line.get(i - 1);
            NavPoint b = line.get(i);
            along[i] = along[i - 1] + Math.sqrt(sq(b.x() - a.x()) + sq(b.y() - a.y()) + sq(b.z() - a.z()));
        }
        this.corners = new CornerClearance(line);
    }

    /** The points with x and z on the {@link #LINE_GRID} (#958); heights as they are. */
    static List<NavPoint> onGrid(List<NavPoint> points) {
        List<NavPoint> out = new ArrayList<>(points.size());
        for (NavPoint p : points) {
            out.add(onGrid(p));
        }
        return List.copyOf(out);
    }

    /** A point with x and z on the {@link #LINE_GRID} (#958): the nearest multiple of {@code 1 / LINE_GRID}. */
    static NavPoint onGrid(NavPoint p) {
        double x = Math.rint(p.x() * LINE_GRID) / LINE_GRID;
        double z = Math.rint(p.z() * LINE_GRID) / LINE_GRID;
        return x == p.x() && z == p.z() ? p : new NavPoint(p.world(), x, p.y(), z);
    }

    /** Whether the line point at {@code index} is a cell of a walk the walk search planned (link C1). */
    public boolean walked(int index) {
        return walked != null && index >= 0 && index < walked.length && walked[index];
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

    /** The junctions the line passes that may be plazas, each at its line point (link M5). */
    public List<PlazaOnLine> plazas() {
        return plazas;
    }

    /** The plaza crossings whose junction lies at a line index in [from, to). */
    public List<PlazaCrossing> plazasBetween(int from, int to) {
        List<PlazaCrossing> out = new ArrayList<>();
        for (PlazaOnLine p : plazas) {
            if (p.index() >= from && p.index() < to) {
                out.add(p.crossing());
            }
        }
        return out;
    }

    /**
     * Where a piece of the line from {@code start} to {@code end} (exclusive) should end so that it holds the whole
     * way across every plaza whose footprint (at most {@code plazaBlocks}) it reaches into: a plaza line is placed from
     * where the route enters to where it leaves, in one piece (link M5).
     */
    public int endHoldingPlazas(int start, int end, int plazaBlocks) {
        int reach = (int) Math.ceil(plazaBlocks / STEP) + 2;
        int out = end;
        for (PlazaOnLine p : plazas) {
            int spanEnd = Math.min(line.size() - 1, p.index() + reach);
            if (start <= spanEnd && out > p.index() - reach && out <= spanEnd) {
                out = spanEnd + 1;
            }
        }
        return out;
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
        int plazaBlocks = steering == null ? 0 : steering.options().plazaBlocks();
        if (plazaBlocks > 0) {
            // at most one stretch more: junctions close together (a street grid) must not chain into a long stretch
            end = Math.min(Math.min(line.size(), start + 2 * perStretch), endHoldingPlazas(start, end, plazaBlocks));
        }
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
        boolean anyWalked = false;
        for (int i = start; i < end; i++) {
            NavPoint p = line.get(i);
            // a walk search's ladder cell (link H1) has no floor to stand on: the body climbs there, as the walk says
            int ownFloor = (int) Math.round(p.y());
            NavPoint floor = walked(i) && NpcClimbables.climbCell(world, new NavPoint(p.world(), p.x(), ownFloor + 1,
                p.z())) ? new NavPoint(p.world(), p.x(), ownFloor, p.z()) : null;
            // a walk search's cell stands on its own floor (link C1): no continuity snap onto another level
            if (floor == null && walked(i)) {
                floor = NavGeometry.floorNear(world, p, ownFloor);
            }
            anyWalked |= walked(i);
            if (floor == null) {
                floor = HopPlanner.surfaceNear(world, p, refY);
            }
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
        List<PlazaCrossing> here = plazaBlocks > 0 ? plazasBetween(start, end) : List.of();
        // a walk the walk search planned is walked as it is (link C1): pulled straight in 2D it would cut a spiral stair
        boolean shape = steering != null && points.size() >= 2 && !anyWalked
            && (!steering.options().plazaOnly() || !here.isEmpty());
        if (shape) {
            // plaza lines only (a group's leader): from the block centres as today, only the plazas change
            CorridorShaper.ShapedLine shaped = steering.shaper().shape(world, steering.options().plazaOnly() ? points
                : shapingInput(points, indexes), indexes, steering.options(), end == line.size(), here);
            points = new ArrayList<>(shaped.waypoints());
            indexes = new ArrayList<>(shaped.lineIndexes());
            chokepoints = shaped.chokepoints();
        }
        if (cornerClearance > 0 && !anyWalked) {
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
            NavGeometry.floorYOf(feet.y()), maxAlong, mayStand);
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

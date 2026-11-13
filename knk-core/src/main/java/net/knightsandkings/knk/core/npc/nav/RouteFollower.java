package net.knightsandkings.knk.core.npc.nav;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
    /** A recovery leg aims this many line points past the point nearest the NPC. */
    public static final int RECOVERY_AHEAD = 4;

    public enum Kind {
        /** Waypoints to follow. */
        WAYPOINTS,
        /** The stretch reaches a chunk that is not loaded: nothing was handed out. */
        UNLOADED,
        /** Nothing left of the line (or the rest collapsed onto the last waypoint). */
        DONE
    }

    /** @param waypoints feet positions at block centres, for {@link Kind#WAYPOINTS} */
    public record Stretch(Kind kind, List<NavPoint> waypoints) {
    }

    private final List<NavPoint> line;
    /** Where the next stretch starts. */
    private int next;
    /** The stretch last handed out: [sentStart, sentEnd). */
    private int sentStart;
    private int sentEnd;
    private int lastSnapY = Integer.MIN_VALUE;
    private int lastSnapEnd = -1;
    private int bestIndex = -1;
    private List<NavPoint> lastSent = List.of();

    /** @param roadLine the route as floor points (the road's polyline), at least one */
    public RouteFollower(List<NavPoint> roadLine) {
        Objects.requireNonNull(roadLine, "roadLine");
        if (roadLine.isEmpty()) {
            throw new IllegalArgumentException("a route needs at least one point");
        }
        this.line = List.copyOf(HopPlanner.resampleKeepingCorners(roadLine, STEP));
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
        int lastX = Integer.MIN_VALUE;
        int lastY = Integer.MIN_VALUE;
        int lastZ = Integer.MIN_VALUE;
        int refY = start == lastSnapEnd && lastSnapY != Integer.MIN_VALUE ? lastSnapY : npcFloorY;
        for (NavPoint p : line.subList(start, end)) {
            NavPoint floor = HopPlanner.snapNear(world, p, refY);
            refY = (int) Math.round(floor.y());
            int bx = floor.blockX();
            int bz = floor.blockZ();
            if (bx == lastX && refY == lastY && bz == lastZ) {
                continue;
            }
            points.add(new NavPoint(p.world(), bx + 0.5, refY + 1, bz + 0.5));
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
        lastSent = List.copyOf(points);
        return new Stretch(Kind.WAYPOINTS, lastSent);
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
     * Stuck on the line: the floor point {@link #RECOVERY_AHEAD} points past the one nearest the NPC (horizontally,
     * from the stretch in hand on), snapped to a floor, for one local leg; the next stretch starts right after it.
     */
    public NavPoint recoveryTarget(NavWorld world, NavPoint feet) {
        int nearest = sentStart;
        double best = Double.MAX_VALUE;
        for (int i = sentStart; i < Math.min(line.size(), sentEnd + 4); i++) {
            double d = line.get(i).horizontalDistance(feet);
            if (d < best) {
                best = d;
                nearest = i;
            }
        }
        int target = Math.min(line.size() - 1, nearest + RECOVERY_AHEAD);
        next = target + 1;
        sentStart = sentEnd = next;
        bestIndex = Math.max(bestIndex, target);
        return HopPlanner.snapToFloor(world, line.get(target));
    }

    private static double sq(double v) {
        return v * v;
    }
}

package net.knightsandkings.knk.core.npc.nav;

import java.util.List;
import java.util.function.Predicate;

/**
 * Where a stuck NPC or a follower's own leg back aims (implementation plan 4b.2 item 5; BEHAVIOR_MODEL NAV-20): a
 * point <b>along the path</b>, ahead of the NPC's place on it, on the NPC's own floor. One rule for the recovery leg,
 * a follower's own leg back and the short-trip test:
 * <ol>
 *     <li>the search runs forward from the NPC's place on the path, never backwards past it;</li>
 *     <li>floors are chained point by point from the floor the NPC stands on ({@link HopPlanner#floorNear}, floor
 *     continuity), so the chain stays on the NPC's floor; a point with no floor within continuity ends the search;</li>
 *     <li>the target is the first point at least {@link #MIN_AHEAD} blocks along the path whose floor has the body's
 *     headroom, fits the low-headroom rule and lies where the current rules let the NPC stand; at most
 *     {@code maxAlong} blocks along the path.</li>
 * </ol>
 * Pure; main thread (it asks the world).
 */
public final class PathTargets {

    /** A target lies at least this many blocks along the path ahead of the NPC. */
    public static final double MIN_AHEAD = 3;

    /** @param index the path index of the target; @param floor its floor point on the NPC's floor */
    public record Target(int index, NavPoint floor) {
    }

    private PathTargets() {
    }

    /**
     * The target ahead on the NPC's own floor, or null when there is none within {@code maxAlong} blocks.
     *
     * @param path      floor points of the path (a route line or a group's path)
     * @param fromIndex the NPC's place on the path (its nearest index, height counted)
     * @param lastIndex the last path index the target may be (a follower's slot), inclusive
     * @param npcFloorY the floor the NPC stands on
     * @param mayStand  whether the current rules let the NPC stand at a feet position (its regions)
     */
    public static Target ahead(NavWorld world, List<NavPoint> path, int fromIndex, int lastIndex, int npcFloorY,
                               double maxAlong, Predicate<NavPoint> mayStand) {
        return ahead(world, path, fromIndex, fromIndex, lastIndex, npcFloorY, maxAlong, mayStand);
    }

    /**
     * As {@link #ahead(NavWorld, List, int, int, int, double, Predicate)}, the target no earlier than {@code minIndex}
     * (a target that failed from this spot is not aimed at again); distances still count from the NPC's place.
     */
    public static Target ahead(NavWorld world, List<NavPoint> path, int fromIndex, int minIndex, int lastIndex,
                               int npcFloorY, double maxAlong, Predicate<NavPoint> mayStand) {
        if (path.isEmpty() || fromIndex < 0 || fromIndex >= path.size()) {
            return null;
        }
        int last = Math.min(lastIndex, path.size() - 1);
        int refY = npcFloorY;
        NavPoint prevFloor = null;
        double along = 0;
        for (int i = fromIndex; i <= last; i++) {
            NavPoint p = path.get(i);
            NavPoint floor = HopPlanner.floorNear(world, p, refY);
            if (floor == null) {
                return null; // the NPC's floor ends here: never a target on another floor
            }
            if (i > fromIndex) {
                NavPoint q = path.get(i - 1);
                along += Math.sqrt(sq(p.x() - q.x()) + sq(p.y() - q.y()) + sq(p.z() - q.z()));
                if (along > maxAlong) {
                    return null;
                }
            }
            boolean jumpOk = prevFloor == null || NavGeometry.jumpFits(world, prevFloor, floor);
            if (i >= minIndex && along >= MIN_AHEAD && jumpOk && NavGeometry.hasHeadroom(world, floor)
                && (mayStand == null || mayStand.test(HopPlanner.feet(floor)))) {
                return new Target(i, floor);
            }
            refY = floor.blockY();
            prevFloor = floor;
        }
        return null;
    }

    /**
     * The short-trip test of {@code planRoad} (implementation plan 4b.2 item 5.4): a goal is walked as one local leg
     * without a road only when it is within {@code hopBlocks} horizontally <b>and</b> within
     * {@link HopPlanner#CONTINUITY_RANGE} blocks of height; a goal 37 blocks up the stairs four blocks away is a trip
     * for the road.
     */
    public static boolean shortTrip(NavPoint fromFloor, NavPoint toFloor, double hopBlocks) {
        return fromFloor.horizontalDistance(toFloor) <= hopBlocks
            && Math.abs(fromFloor.y() - toFloor.y()) <= HopPlanner.CONTINUITY_RANGE;
    }

    private static double sq(double v) {
        return v * v;
    }
}

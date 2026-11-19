package net.knightsandkings.knk.core.npc.nav;

import java.util.List;

import net.knightsandkings.knk.core.navigation.shape.NavPoint;

/**
 * The margin within which the engine counts a waypoint as reached on a list that climbs (link J1,
 * PandiO/knk-workspace#695). Citizens moves on to the next waypoint once the body is within the list's margin across
 * and less than a block above or below the current one; Minecraft's move control then jumps whenever the waypoint it
 * steers to is more than a step (0.6) above the feet and less than a block away across. On a stair with the margin at
 * 0.5 or 1.0, the step's waypoint (half a block up) counts as reached while the body still stands beside the step on
 * the lower level, and the next waypoint (a block above the feet) is then less than a block away: a jump toward a
 * waypoint. Found in {@code npc-replay-quarry-to-keep} at the hall entry (margin 1.0, the step 0.66 across, the next
 * waypoint 1.0 up and 0.99 across) and behind the climb's CI failure on flight 1.
 * <p>
 * A body on the lower level has no part over the step's cell, so its middle is at least half its width
 * ({@value #BODY_HALF_WIDTH}) outside that cell, and the step's waypoint lies in that cell. With a margin under that, a
 * step counts as reached only once the body is on it. Pure.
 */
public final class ReachMargin {

    /** Half a player body's width: a body whose middle is this far from a cell has no part over it. */
    public static final double BODY_HALF_WIDTH = 0.3;
    /** The margin on a list that climbs: under {@link #BODY_HALF_WIDTH}. */
    public static final double CLIMB = 0.25;
    /** A rise above this between two waypoints (or the feet and the first) is a climb; a path block's 1/16 is not. */
    public static final double RISE = 0.25;

    private ReachMargin() {
    }

    /**
     * The margin to hand the engine with {@code waypoints}: {@code margin}, or {@link #CLIMB} when that is smaller and
     * the list climbs.
     *
     * @param feet      the body's feet now, or null (then only the list itself is looked at)
     * @param waypoints the waypoints as handed to the engine (feet at the walking surface)
     * @param margin    the margin the caller asks for
     */
    public static double of(NavPoint feet, List<NavPoint> waypoints, double margin) {
        return margin > CLIMB && climbs(feet, waypoints) ? CLIMB : margin;
    }

    /** Whether a waypoint is more than {@link #RISE} above the one before it (the first: above {@code feet}). */
    public static boolean climbs(NavPoint feet, List<NavPoint> waypoints) {
        double prev = feet == null ? Double.NaN : feet.y();
        for (NavPoint p : waypoints) {
            if (!Double.isNaN(prev) && p.y() - prev > RISE) {
                return true;
            }
            prev = p.y();
        }
        return false;
    }
}

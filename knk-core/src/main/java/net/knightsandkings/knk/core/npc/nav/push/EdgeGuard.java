package net.knightsandkings.knk.core.npc.nav.push;

import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.navigation.shape.NavWorld;
import net.knightsandkings.knk.core.npc.nav.HopPlanner;

/**
 * The edge guard (implementation plan 4c.4 item 7, research report § 6.5 item 5): with collision on an NPC never stands
 * on a cell next to a drop it did not choose, so a push has a cell of margin to absorb before the body falls. A drop is
 * the corner-clearance rule's (L5): a loaded neighbour cell a body can enter at feet and head height with no floor
 * within {@link HopPlanner#CONTINUITY_RANGE} blocks of the NPC's floor (a fall of three blocks or more). Pure; main
 * thread (asks the world).
 */
public final class EdgeGuard {

    /** The eight neighbour offsets, cardinals first. */
    private static final int[][] NEIGHBOURS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private EdgeGuard() {
    }

    /** Whether one of the eight cells around the feet position is a drop for a body standing there. */
    public static boolean nextToDrop(NavWorld world, NavPoint feet) {
        return dropSide(world, feet) != null;
    }

    /**
     * The offset {@code {dx, dz}} of the first neighbour cell (cardinals first) that is a drop for a body standing at
     * {@code feet}, or null when there is none.
     */
    public static int[] dropSide(NavWorld world, NavPoint feet) {
        String w = feet.world();
        int floorY = feet.blockY() - 1;
        for (int[] n : NEIGHBOURS) {
            int x = feet.blockX() + n[0];
            int z = feet.blockZ() + n[1];
            if (!world.isLoaded(w, x, z) || world.blocksBody(w, x, floorY + 1, z) || world.blocksBody(w, x, floorY + 2, z)) {
                continue; // unknown, or a wall: nothing to fall down
            }
            if (HopPlanner.floorNear(world, new NavPoint(w, x + 0.5, floorY, z + 0.5), floorY) == null) {
                return n;
            }
        }
        return null;
    }

    /**
     * Whether a body moved from {@code from} to {@code to} came nearer a drop: it stands next to one now, and the
     * horizontal move points towards that drop's side (within 90°).
     */
    public static boolean towardsDrop(NavWorld world, NavPoint from, NavPoint to) {
        int[] side = dropSide(world, to);
        if (side == null) {
            return false;
        }
        double dx = to.x() - from.x();
        double dz = to.z() - from.z();
        return dx * side[0] + dz * side[1] > 0 || Math.hypot(dx, dz) < 1e-6;
    }
}

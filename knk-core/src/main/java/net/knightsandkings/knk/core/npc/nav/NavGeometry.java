package net.knightsandkings.knk.core.npc.nav;

import java.util.Locale;

/**
 * Small block-geometry rules NPC navigation shares (implementation plan 4b.2 items 1, 3, 4 and 13): the body's size,
 * walls at feet and head height, the jump headroom and whether a straight walk between two waypoints stays on
 * standable floor. Pure functions over {@link NavWorld}; main thread.
 */
public final class NavGeometry {

    /** A walking NPC's width (a player's hitbox). */
    public static final double BODY_WIDTH = 0.6;
    /** A walking NPC's height (a player's hitbox). */
    public static final double BODY_HEIGHT = 1.8;
    /** Free blocks above the take-off cell a jump up a full block needs (implementation plan 4b.2 item 4). */
    public static final double JUMP_HEADROOM = 3.0;
    /** Samples per block along a straight walk. */
    private static final int SAMPLES_PER_BLOCK = 4;

    private NavGeometry() {
    }

    /** Whether a wall stops a body in the cell at feet height {@code feetY}: at feet or at head height. */
    public static boolean wallAt(NavWorld world, String w, int x, int feetY, int z) {
        return world.blocksBody(w, x, feetY, z) || world.blocksBody(w, x, feetY + 1, z);
    }

    /** Whether a body has its headroom standing on this floor. */
    public static boolean hasHeadroom(NavWorld world, NavPoint floor) {
        return world.freeHeight(floor.world(), floor.blockX(), floor.blockY(), floor.blockZ()) >= BODY_HEIGHT;
    }

    /**
     * The low-headroom rule (implementation plan 4b.2 item 4): stepping from floor {@code from} onto floor {@code to}
     * a full block higher needs {@link #JUMP_HEADROOM} free blocks above {@code from}; a step of less, or down, is
     * always fine.
     */
    public static boolean jumpFits(NavWorld world, NavPoint from, NavPoint to) {
        return to.blockY() <= from.blockY()
            || world.freeHeight(from.world(), from.blockX(), from.blockY(), from.blockZ()) >= JUMP_HEADROOM;
    }

    /**
     * The shortcut check (implementation plan 4b.2 item 3): whether a body (its width, {@link #BODY_WIDTH}) walking
     * the straight line between two waypoints (feet positions) crosses only cells that are standable, have the body's
     * headroom and keep floor continuity with both ends. A diagonal across air, a ledge or a wall corner is refused.
     */
    public static boolean straightWalkable(NavWorld world, NavPoint fromFeet, NavPoint toFeet) {
        int fromFloor = fromFeet.blockY() - 1;
        int toFloor = toFeet.blockY() - 1;
        double dx = toFeet.x() - fromFeet.x();
        double dz = toFeet.z() - fromFeet.z();
        double len = Math.hypot(dx, dz);
        int samples = Math.max(1, (int) Math.ceil(len * SAMPLES_PER_BLOCK));
        double half = BODY_WIDTH / 2;
        java.util.Set<Long> checked = new java.util.HashSet<>();
        for (int i = 0; i <= samples; i++) {
            double t = (double) i / samples;
            double x = fromFeet.x() + dx * t;
            double z = fromFeet.z() + dz * t;
            for (int c = 0; c < 4; c++) {
                int bx = (int) Math.floor(x + ((c & 1) == 0 ? -half : half));
                int bz = (int) Math.floor(z + ((c & 2) == 0 ? -half : half));
                if (!checked.add(((long) bx << 32) ^ (bz & 0xffffffffL))) {
                    continue;
                }
                NavPoint floor = HopPlanner.floorNear(world, new NavPoint(fromFeet.world(), bx + 0.5, fromFloor, bz + 0.5),
                    fromFloor);
                if (floor == null || Math.abs(floor.blockY() - toFloor) > HopPlanner.CONTINUITY_RANGE
                    || !hasHeadroom(world, floor)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** A compass word for a horizontal offset (north is -z): "north", "south-east", or "here". */
    public static String face(int dx, int dz) {
        String ns = dz < 0 ? "north" : dz > 0 ? "south" : "";
        String ew = dx > 0 ? "east" : dx < 0 ? "west" : "";
        if (ns.isEmpty() && ew.isEmpty()) {
            return "here";
        }
        return ns.isEmpty() ? ew : ew.isEmpty() ? ns : ns + "-" + ew;
    }

    /** {@code x y z} of a point's block. */
    public static String block(NavPoint p) {
        return p == null ? "-" : String.format(Locale.ROOT, "%d %d %d", p.blockX(), p.blockY(), p.blockZ());
    }
}

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
    /**
     * How far a body walks up without jumping (the engine's step height): a half-block stair step or a slab's half
     * block is a step, a full block is a jump (link B4, PandiO/knk-workspace#673).
     */
    public static final double STEP_UP = 0.6;
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
            || surfaceY(world, to) - surfaceY(world, from) <= STEP_UP + 1e-6 // a half-block step up: no jump (B4)
            || world.freeHeight(from.world(), from.blockX(), from.blockY(), from.blockZ()) >= JUMP_HEADROOM;
    }

    /**
     * The floor block a feet height stands on: the block under whole-block feet ({@code 73} stands on {@code 72}),
     * the block the feet are in on a slab or a stair's half block ({@code 72.5} stands on {@code 72}). Link B4.
     */
    public static int floorYOf(double feetY) {
        return (int) Math.ceil(feetY - 0.01) - 1;
    }

    /** The height the feet rest at on a floor point ({@link NavWorld#surfaceY}): its block's top under the body. */
    public static double surfaceY(NavWorld world, NavPoint floor) {
        return world.surfaceY(floor.world(), floor.x(), floor.blockY(), floor.z());
    }

    /**
     * A feet position at the real walking surface (link B4, PandiO/knk-workspace#673): on a bottom slab half a block
     * lower than the whole-block feet height navigation plans with ({@code floor + 1}), on a full block unchanged.
     * Waypoints go to the engine this way, so it walks up a half-block stair step by step instead of jumping to a
     * whole-block height, and a body that stands on its waypoint is at it. A position whose floor is not standable
     * (in the air, an unloaded chunk) is left as it is. Idempotent.
     */
    public static NavPoint onSurface(NavWorld world, NavPoint feet) {
        if (feet == null) {
            return null;
        }
        int floorY = floorYOf(feet.y());
        int x = feet.blockX();
        int z = feet.blockZ();
        if (!world.isStandableFloor(feet.world(), x, floorY, z)) {
            return feet;
        }
        double y = world.surfaceY(feet.world(), feet.x(), floorY, feet.z());
        return Math.abs(y - feet.y()) < 1e-9 ? feet : new NavPoint(feet.world(), feet.x(), y, feet.z());
    }

    /**
     * {@link #onSurface(NavWorld, NavPoint)} for every waypoint of a list, and the step between two waypoints that rise
     * more than {@link #STEP_UP} (link B4): where the line from one to the next crosses a cell whose surface lies in
     * between (a stair's step or a slab before a full block), that cell's point on the line goes in, so the engine
     * never has a target a whole block above the feet within a block (its move control jumps for that). The list
     * itself when nothing changes.
     */
    public static java.util.List<NavPoint> onSurface(NavWorld world, java.util.List<NavPoint> feet) {
        java.util.List<NavPoint> out = null;
        NavPoint prev = null;
        for (int i = 0; i < feet.size(); i++) {
            NavPoint p = feet.get(i);
            NavPoint q = onSurface(world, p);
            java.util.List<NavPoint> steps = prev == null ? java.util.List.of() : stepsBetween(world, prev, q);
            if ((q != p || !steps.isEmpty()) && out == null) {
                out = new java.util.ArrayList<>(feet.subList(0, i));
            }
            if (out != null) {
                out.addAll(steps);
                out.add(q);
            }
            prev = q;
        }
        return out == null ? feet : java.util.List.copyOf(out);
    }

    /**
     * The points on the line from {@code a} to {@code b} (feet at the surface) in the cells between them whose surface
     * lies between theirs, rising; empty when {@code b} is at most {@link #STEP_UP} above {@code a}.
     */
    static java.util.List<NavPoint> stepsBetween(NavWorld world, NavPoint a, NavPoint b) {
        if (b.y() - a.y() <= STEP_UP + 1e-6 || !a.world().equals(b.world())) {
            return java.util.List.of();
        }
        double len = a.horizontalDistance(b);
        int samples = Math.max(1, (int) Math.ceil(len * SAMPLES_PER_BLOCK));
        java.util.List<NavPoint> out = new java.util.ArrayList<>();
        double last = a.y();
        int refY = floorYOf(a.y());
        long cellA = cell(a.blockX(), a.blockZ());
        long cellB = cell(b.blockX(), b.blockZ());
        long seen = cellA;
        for (int i = 1; i < samples; i++) {
            double t = (double) i / samples;
            NavPoint at = new NavPoint(a.world(), a.x() + (b.x() - a.x()) * t, a.y(), a.z() + (b.z() - a.z()) * t);
            long c = cell(at.blockX(), at.blockZ());
            if (c == seen || c == cellB) {
                continue;
            }
            seen = c;
            NavPoint floor = HopPlanner.floorNear(world, new NavPoint(a.world(), at.x(), refY, at.z()), refY);
            if (floor == null) {
                continue;
            }
            double surface = world.surfaceY(a.world(), at.x(), floor.blockY(), at.z());
            refY = floor.blockY();
            if (surface > last + 1e-6 && surface < b.y() - 1e-6) {
                out.add(new NavPoint(a.world(), at.x(), surface, at.z()));
                last = surface;
            }
        }
        return out;
    }

    private static long cell(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
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

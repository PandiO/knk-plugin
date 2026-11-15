package net.knightsandkings.knk.core.npc.nav;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * The small moves of the recovery ladder (implementation plan 4b.2 items 6.2 and 13): the blocking face beside a
 * stuck NPC, whether it is "near a wall", and the side-step away from that face. Pure; main thread (asks the world).
 */
public final class RecoveryMoves {

    /** The eight neighbour offsets, cardinals first. */
    private static final int[][] NEIGHBOURS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /**
     * A cell that stops a body next to the NPC.
     *
     * @param x  the wall block, feet height
     * @param dx offset from the NPC's block, -1 to 1
     */
    public record Wall(int x, int y, int z, int dx, int dz) {

        /** Compass word of the face as seen from the NPC ("north" is -z). */
        public String face() {
            return NavGeometry.face(dx, dz);
        }

        @Override
        public String toString() {
            return x + " " + y + " " + z + " (" + face() + ")";
        }
    }

    private RecoveryMoves() {
    }

    /** The neighbour offset nearest a walking direction ({@code dirX, dirZ}), or null without one. */
    static int[] ahead(double dirX, double dirZ) {
        double len = Math.hypot(dirX, dirZ);
        if (len < 1e-9) {
            return null;
        }
        double ux = dirX / len;
        double uz = dirZ / len;
        int dx = ux > 0.38 ? 1 : ux < -0.38 ? -1 : 0;
        int dz = uz > 0.38 ? 1 : uz < -0.38 ? -1 : 0;
        return dx == 0 && dz == 0 ? null : new int[] {dx, dz};
    }

    /**
     * The blocking face (implementation plan 4b.2 item 6.2): the nearest cell within one block that stops a body at
     * feet or head height, the one in the walking direction first; null when there is none.
     */
    public static Wall blockingFace(NavWorld world, NavPoint feet, double dirX, double dirZ) {
        String w = feet.world();
        int bx = feet.blockX();
        int y = feet.blockY();
        int bz = feet.blockZ();
        int[] ahead = ahead(dirX, dirZ);
        List<int[]> order = new ArrayList<>();
        if (ahead != null) {
            order.add(ahead);
        }
        List<int[]> rest = new ArrayList<>(List.of(NEIGHBOURS));
        rest.sort(Comparator.comparingInt((int[] o) -> Math.abs(o[0]) + Math.abs(o[1]))
            .thenComparingDouble(o -> -(o[0] * dirX + o[1] * dirZ)));
        order.addAll(rest);
        for (int[] o : order) {
            if (NavGeometry.wallAt(world, w, bx + o[0], y, bz + o[1])) {
                return new Wall(bx + o[0], y, bz + o[1], o[0], o[1]);
            }
        }
        return null;
    }

    /**
     * "Near a wall" (implementation plan 4b.2 item 13): a cell that stops a body at feet or head height touches the
     * NPC's cell (one of its four sides) or is the next cell in its walking direction.
     */
    public static boolean nearWall(NavWorld world, NavPoint feet, double dirX, double dirZ) {
        String w = feet.world();
        int bx = feet.blockX();
        int y = feet.blockY();
        int bz = feet.blockZ();
        for (int i = 0; i < 4; i++) {
            if (NavGeometry.wallAt(world, w, bx + NEIGHBOURS[i][0], y, bz + NEIGHBOURS[i][1])) {
                return true;
            }
        }
        int[] ahead = ahead(dirX, dirZ);
        return ahead != null && NavGeometry.wallAt(world, w, bx + ahead[0], y, bz + ahead[1]);
    }

    /**
     * The side-step (implementation plan 4b.2 item 6.2): one block straight away from the blocking face, or
     * diagonally back when that block is not standable. With {@code awayFrom} given (a follower: the direction away
     * from the path's centre), the candidates nearest that direction come first. The target keeps floor continuity
     * with the NPC's floor (a step of at most one block), has the body's headroom, fits the low-headroom rule, is no
     * wall itself and lies where {@code mayStand} lets the NPC stand. Feet at the block's centre, or null.
     */
    public static NavPoint sideStep(NavWorld world, NavPoint feet, Wall wall, double[] awayFrom,
                                    Predicate<NavPoint> mayStand) {
        if (wall == null) {
            return null;
        }
        List<int[]> candidates = new ArrayList<>();
        candidates.add(new int[] {-wall.dx(), -wall.dz()});
        if (wall.dx() == 0 || wall.dz() == 0) {
            int px = wall.dz();
            int pz = wall.dx();
            candidates.add(new int[] {-wall.dx() + px, -wall.dz() + pz});
            candidates.add(new int[] {-wall.dx() - px, -wall.dz() - pz});
        } else {
            candidates.add(new int[] {-wall.dx(), 0});
            candidates.add(new int[] {0, -wall.dz()});
        }
        if (awayFrom != null) {
            candidates.sort(Comparator.comparingDouble(o -> -(o[0] * awayFrom[0] + o[1] * awayFrom[1])));
        }
        NavPoint floorHere = HopPlanner.floor(feet);
        for (int[] o : candidates) {
            NavPoint probe = new NavPoint(feet.world(), feet.blockX() + o[0] + 0.5, floorHere.blockY(),
                feet.blockZ() + o[1] + 0.5);
            NavPoint floor = HopPlanner.floorNear(world, probe, floorHere.blockY());
            if (floor == null || Math.abs(floor.blockY() - floorHere.blockY()) > 1 || !NavGeometry.hasHeadroom(world, floor)
                || !NavGeometry.jumpFits(world, floorHere, floor)) {
                continue;
            }
            NavPoint target = HopPlanner.feet(floor);
            if (NavGeometry.wallAt(world, feet.world(), target.blockX(), target.blockY(), target.blockZ())
                || (mayStand != null && !mayStand.test(target))) {
                continue;
            }
            return target;
        }
        return null;
    }
}

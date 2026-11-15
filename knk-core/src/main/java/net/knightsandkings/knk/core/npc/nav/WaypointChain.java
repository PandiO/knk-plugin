package net.knightsandkings.knk.core.npc.nav;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps a list of waypoints walkable step by step (PandiO/knk-workspace#592; BEHAVIOR_MODEL NAV-19): the engine walks
 * straight from one waypoint to the next, so every step must be a straight walk on floor
 * ({@link NavGeometry#straightWalkable}). Where one is not, typically the diagonal across the inside of a turn at a
 * ledge or round a wall corner, the cells between the two are searched on the walk (4-neighbour moves, a floor at most
 * {@link #STEP_HEIGHT} up or down from the cell before, the body's headroom) in a small box round them, and the turns
 * of that cell path go in as extra waypoints. When there is no such path the step stays as it was: nothing is
 * dropped. Pure; main thread.
 */
public final class WaypointChain {

    /** How far a step between two neighbouring cells may climb or descend. */
    public static final int STEP_HEIGHT = 1;
    /** Blocks the search box reaches past the two waypoints. */
    static final int MARGIN = 3;
    /** The search box's largest side: a step longer than this is left alone. */
    static final int MAX_SIDE = 24;

    private WaypointChain() {
    }

    /**
     * The waypoints with every step a straight walk where the walk allows it.
     *
     * @param from      where the walk starts (the NPC's feet, or the last waypoint it was given), or null; it is
     *                  not part of the result
     * @param waypoints feet positions
     */
    public static List<NavPoint> bridged(NavWorld world, NavPoint from, List<NavPoint> waypoints) {
        if (waypoints.isEmpty()) {
            return waypoints;
        }
        List<NavPoint> out = new ArrayList<>(waypoints.size() + 4);
        NavPoint prev = from;
        for (NavPoint p : waypoints) {
            if (prev != null && !NavGeometry.straightWalkable(world, prev, p)) {
                out.addAll(bridge(world, prev, p));
            }
            out.add(p);
            prev = p;
        }
        return out;
    }

    /**
     * The extra waypoints between two feet positions whose straight walk leaves the floor: the turns of the shortest
     * cell path on the walk, each step between them a straight walk. Empty when there is no such path in the box.
     */
    static List<NavPoint> bridge(NavWorld world, NavPoint a, NavPoint b) {
        String w = a.world();
        int ax = a.blockX();
        int az = a.blockZ();
        int bx = b.blockX();
        int bz = b.blockZ();
        if (!w.equals(b.world()) || (ax == bx && az == bz)) {
            return List.of();
        }
        int minX = Math.min(ax, bx) - MARGIN;
        int maxX = Math.max(ax, bx) + MARGIN;
        int minZ = Math.min(az, bz) - MARGIN;
        int maxZ = Math.max(az, bz) + MARGIN;
        if (maxX - minX + 1 > MAX_SIDE || maxZ - minZ + 1 > MAX_SIDE) {
            return List.of();
        }
        int goalFloor = b.blockY() - 1;
        long start = key(ax, az);
        Map<Long, Integer> floors = new HashMap<>();
        Map<Long, Long> cameFrom = new HashMap<>();
        floors.put(start, a.blockY() - 1);
        ArrayDeque<Long> queue = new ArrayDeque<>();
        queue.add(start);
        long goal = Long.MIN_VALUE;
        int[][] moves = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!queue.isEmpty() && goal == Long.MIN_VALUE) {
            long cell = queue.poll();
            int cx = (int) (cell >> 32);
            int cz = (int) cell;
            int floor = floors.get(cell);
            for (int[] m : moves) {
                int nx = cx + m[0];
                int nz = cz + m[1];
                long next = key(nx, nz);
                if (nx < minX || nx > maxX || nz < minZ || nz > maxZ || floors.containsKey(next)) {
                    continue;
                }
                Integer f = stepFloor(world, w, nx, nz, floor, nx == bx && nz == bz ? goalFloor : Integer.MIN_VALUE);
                if (f == null) {
                    continue;
                }
                floors.put(next, f);
                cameFrom.put(next, cell);
                if (nx == bx && nz == bz) {
                    goal = next;
                    break;
                }
                queue.add(next);
            }
        }
        if (goal == Long.MIN_VALUE) {
            return List.of();
        }
        List<NavPoint> cells = new ArrayList<>();
        for (long c = cameFrom.get(goal); c != start; c = cameFrom.get(c)) {
            cells.add(0, new NavPoint(w, (int) (c >> 32) + 0.5, floors.get(c) + 1, (int) c + 0.5));
        }
        cells.add(b);
        // the turns only: from where it stands, on to the furthest cell it reaches in a straight walk
        List<NavPoint> out = new ArrayList<>();
        NavPoint at = a;
        int i = 0;
        while (i < cells.size()) {
            int reach = i;
            for (int j = cells.size() - 1; j > i; j--) {
                if (NavGeometry.straightWalkable(world, at, cells.get(j))) {
                    reach = j;
                    break;
                }
            }
            if (reach == cells.size() - 1) {
                break; // b itself follows
            }
            at = cells.get(reach);
            out.add(at);
            i = reach + 1;
        }
        return out;
    }

    /**
     * The floor a body steps onto in a cell from a floor at {@code fromFloor}: within {@link #STEP_HEIGHT}, its
     * headroom free, the goal cell's own floor preferred; null when it cannot step there.
     */
    private static Integer stepFloor(NavWorld world, String w, int x, int z, int fromFloor, int goalFloor) {
        if (!world.isLoaded(w, x, z)) {
            return null;
        }
        if (goalFloor != Integer.MIN_VALUE && Math.abs(goalFloor - fromFloor) <= STEP_HEIGHT
            && standable(world, w, x, goalFloor, z)) {
            return goalFloor;
        }
        for (int d : new int[] {0, 1, -1}) {
            if (standable(world, w, x, fromFloor + d, z)) {
                return fromFloor + d;
            }
        }
        return null;
    }

    private static boolean standable(NavWorld world, String w, int x, int floorY, int z) {
        return world.isStandableFloor(w, x, floorY, z) && world.freeHeight(w, x, floorY, z) >= NavGeometry.BODY_HEIGHT;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }
}

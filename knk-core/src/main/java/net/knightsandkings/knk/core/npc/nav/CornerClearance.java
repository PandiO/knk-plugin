package net.knightsandkings.knk.core.npc.nav;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Corner clearance on a route line (implementation plan 4b.2 item 1; BEHAVIOR_MODEL NAV-19): at a turn, the waypoints
 * keep a distance from walls on the inside of the turn, so an NPC walks round a wall corner instead of brushing it
 * and getting stuck.
 *
 * <p>A turn is a line point where the heading changes by {@link #TURN_DEGREES} or more over the next
 * {@link #TURN_SPAN} points. For the turn point and the {@link #NEIGHBOURS} points either side of it, a waypoint
 * moves outwards (away from the inside of the turn), at most the clearance in whole blocks, to the block that keeps
 * the most distance from the walls on the inside up to the clearance (a drop on the inside, a ledge's edge, counts as
 * a wall: PandiO/knk-workspace#592); the block must be standable, keep floor
 * continuity with the waypoint's floor, have the body's headroom, fit the low-headroom rule, lie in the same
 * WorldGuard regions and keep a straight walk ({@link NavGeometry#straightWalkable}) to the waypoints before and
 * after it. Where the road is too narrow, the waypoint goes as far out as the road allows, or stays. Straight stretches
 * are left alone, so a road line may run along a wall. Waypoints stay at block centres.
 *
 * <p>Immutable once built for a line (the turn normals are pure maths on the line); {@link #apply} asks the world.
 */
public final class CornerClearance {

    /** A heading change of this many degrees or more over {@link #TURN_SPAN} points is a turn. */
    public static final double TURN_DEGREES = 30;
    /** Points before and after a point that its heading change is measured over. */
    public static final int TURN_SPAN = 3;
    /** Points either side of a turn point that keep the clearance too. */
    public static final int NEIGHBOURS = 2;

    /** Per line index: the unit vector into the inside of the nearest turn, or null off turns. */
    private final double[][] inside;

    /** @param line floor points of a route (the {@link RouteFollower#line()}) */
    public CornerClearance(List<NavPoint> line) {
        Objects.requireNonNull(line, "line");
        int n = line.size();
        double[][] turns = new double[n][];
        double cos = Math.cos(Math.toRadians(TURN_DEGREES));
        for (int i = 1; i < n - 1; i++) {
            NavPoint before = line.get(Math.max(0, i - TURN_SPAN));
            NavPoint at = line.get(i);
            NavPoint after = line.get(Math.min(n - 1, i + TURN_SPAN));
            double[] in = unit(at.x() - before.x(), at.z() - before.z());
            double[] out = unit(after.x() - at.x(), after.z() - at.z());
            if (in == null || out == null || in[0] * out[0] + in[1] * out[1] > cos) {
                continue;
            }
            turns[i] = unit(out[0] - in[0], out[1] - in[1]);
        }
        inside = new double[n][];
        for (int i = 0; i < n; i++) {
            double[] best = null;
            int bestD = Integer.MAX_VALUE;
            for (int j = Math.max(0, i - NEIGHBOURS); j <= Math.min(n - 1, i + NEIGHBOURS); j++) {
                if (turns[j] != null && Math.abs(j - i) < bestD) {
                    best = turns[j];
                    bestD = Math.abs(j - i);
                }
            }
            inside[i] = best;
        }
    }

    /** Whether the line point at {@code index} is a turn point or within {@link #NEIGHBOURS} points of one. */
    public boolean nearTurn(int index) {
        return index >= 0 && index < inside.length && inside[index] != null;
    }

    /**
     * The waypoints of one stretch with corner clearance applied.
     *
     * @param waypoints   feet positions at block centres
     * @param lineIndexes for each waypoint, the line index it came from
     * @param clearance   blocks to keep from walls on the inside of a turn; 0 = off (the waypoints as they are)
     */
    public List<NavPoint> apply(NavWorld world, List<NavPoint> waypoints, List<Integer> lineIndexes, double clearance) {
        if (!(clearance > 0) || waypoints.isEmpty()) {
            return waypoints;
        }
        List<NavPoint> out = new ArrayList<>(waypoints.size());
        for (int k = 0; k < waypoints.size(); k++) {
            NavPoint p = waypoints.get(k);
            double[] n = inside[lineIndexes.get(k)];
            NavPoint moved = p;
            if (n != null) {
                NavPoint prev = out.isEmpty() ? null : out.get(out.size() - 1);
                NavPoint next = k + 1 < waypoints.size() ? waypoints.get(k + 1) : null;
                moved = clear(world, p, n, clearance, prev, next);
            }
            NavPoint last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last == null || last.blockX() != moved.blockX() || last.blockY() != moved.blockY()
                || last.blockZ() != moved.blockZ()) {
                out.add(moved);
            }
        }
        return out;
    }

    /** One waypoint (feet at a block centre) moved out of the inside of its turn as far as the clearance asks. */
    static NavPoint clear(NavWorld world, NavPoint feet, double[] n, double clearance, NavPoint prev, NavPoint next) {
        String w = feet.world();
        int bx = feet.blockX();
        int feetY = feet.blockY();
        int bz = feet.blockZ();
        int maxMove = (int) Math.floor(clearance + 1e-9);
        int reach = maxMove + (int) Math.ceil(clearance + 0.5) + 1;
        // the walls and drops on the inside of the turn around the waypoint, asked once (feet and head height of each
        // cell's own floor are close enough: candidates keep floor continuity with the waypoint); a ledge on the
        // inside of a turn is kept clear of like a wall (#592, the #491 stair corner)
        Set<Long> walls = new HashSet<>();
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                if ((dx != 0 || dz != 0) && (NavGeometry.wallAt(world, w, bx + dx, feetY, bz + dz)
                    || drop(world, w, bx + dx, feetY - 1, bz + dz))) {
                    walls.add(key(bx + dx, bz + dz));
                }
            }
        }
        double here = distance(walls, bx + 0.5, bz + 0.5, n, clearance);
        if (here >= clearance || maxMove == 0) {
            return feet;
        }
        Set<String> regions = world.regionsAt(w, bx, feetY, bz);
        NavPoint floorHere = HopPlanner.floor(feet);
        NavPoint best = feet;
        double bestD = here;
        int bestMove = 0;
        for (int dx = -maxMove; dx <= maxMove; dx++) {
            for (int dz = -maxMove; dz <= maxMove; dz++) {
                if ((dx == 0 && dz == 0) || dx * n[0] + dz * n[1] >= -1e-9) {
                    continue; // outwards only
                }
                int move = Math.max(Math.abs(dx), Math.abs(dz));
                double d = distance(walls, bx + dx + 0.5, bz + dz + 0.5, n, clearance);
                if (d < bestD - 1e-9 || (Math.abs(d - bestD) < 1e-9 && move >= bestMove) || d <= here + 1e-9) {
                    continue;
                }
                NavPoint floor = HopPlanner.floorNear(world, new NavPoint(w, bx + dx + 0.5, floorHere.y(), bz + dz + 0.5),
                    floorHere.blockY());
                if (floor == null || Math.abs(floor.blockY() - floorHere.blockY()) > 1
                    || !NavGeometry.hasHeadroom(world, floor) || !NavGeometry.jumpFits(world, floorHere, floor)
                    || !regions.equals(world.regionsAt(w, floor.blockX(), floor.blockY() + 1, floor.blockZ()))) {
                    continue;
                }
                NavPoint candidate = HopPlanner.feet(floor);
                if ((prev != null && !NavGeometry.straightWalkable(world, prev, candidate))
                    || (next != null && !NavGeometry.straightWalkable(world, candidate, next))) {
                    continue;
                }
                best = candidate;
                bestD = d;
                bestMove = move;
            }
        }
        return best;
    }

    /**
     * The distance from a point to the nearest wall on the inside of the turn ({@code n} points inwards), capped at
     * {@code cap}.
     */
    private static double distance(Set<Long> walls, double px, double pz, double[] n, double cap) {
        double best = cap;
        for (long wall : walls) {
            int wx = (int) (wall >> 32);
            int wz = (int) wall;
            if ((wx + 0.5 - px) * n[0] + (wz + 0.5 - pz) * n[1] <= 0) {
                continue; // behind or beside the point, not on the inside
            }
            double ddx = Math.max(Math.max(wx - px, 0), px - (wx + 1));
            double ddz = Math.max(Math.max(wz - pz, 0), pz - (wz + 1));
            best = Math.min(best, Math.hypot(ddx, ddz));
        }
        return best;
    }

    /**
     * Whether a cell is a drop beside a waypoint on floor {@code floorY}: loaded, with no floor within
     * {@link HopPlanner#CONTINUITY_RANGE} of it (air over a lower level, a stairwell).
     */
    private static boolean drop(NavWorld world, String w, int x, int floorY, int z) {
        return world.isLoaded(w, x, z) && HopPlanner.floorNear(world, new NavPoint(w, x + 0.5, floorY, z + 0.5), floorY) == null;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    private static double[] unit(double x, double z) {
        double len = Math.hypot(x, z);
        return len < 1e-9 ? null : new double[] {x / len, z / len};
    }
}

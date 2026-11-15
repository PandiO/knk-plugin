package net.knightsandkings.knk.core.npc.nav.corridor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

import net.knightsandkings.knk.core.npc.nav.HopPlanner;
import net.knightsandkings.knk.core.npc.nav.LocalLeg;
import net.knightsandkings.knk.core.npc.nav.NavGeometry;
import net.knightsandkings.knk.core.npc.nav.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NavWorld;

/**
 * K&K's own search for a local leg (implementation plan 4c.3 decision 8, add-on D, link M6; BEHAVIOR_MODEL NAV-24): a
 * short trip, a follower's own leg back or the recovery ladder's local leg, searched on K&K's walk grid with the
 * corridor's clearance per cell instead of the engine's A*.
 *
 * <ol>
 *   <li><b>The grid.</b> A {@link Corridor} round the straight line from the NPC to the leg's target,
 *       {@link #HALF_WIDTH} blocks either side (half the leg's length plus 2 for a longer leg, at most
 *       {@link #MAX_HALF_WIDTH}), flooded from both ends with floor continuity (a step of at most one
 *       block, the body's headroom); WorldGuard regions don't shape it, the leg's own cell access does.</li>
 *   <li><b>The search.</b> A* over its cells, 8 directions, a diagonal only past two open flanking cells (no corner is
 *       cut). A cell is entered only when the leg allows it ({@link LocalLeg#allows}: the subject's gate, door and
 *       region verdicts, the captured box) and its centre keeps the body's half width from every obstacle (the hard
 *       minimum; a one-block door keeps 0.5, so it stays open). Entering a cell costs its length, the leg access's own
 *       extra cost and {@link #CLEARANCE_COST} for each block of clearance it lacks to {@code wall-clearance}, so the
 *       leg keeps off walls where the gap allows. At most {@link #MAX_EXPANSIONS} cells are expanded: past that the
 *       search gives up and the engine's search is the fallback.</li>
 *   <li><b>Smoothing</b> (Theta*-style, on the result): from each anchor the furthest cell of the path whose straight
 *       step stays in the corridor with floor continuity ({@link Corridor#reach}), keeps L5's rule
 *       ({@link NavGeometry#straightWalkable}), passes only allowed cells and keeps, everywhere on it, the least
 *       clearance of the path cells it replaces (at most {@code wall-clearance}).</li>
 * </ol>
 *
 * Pure, over the {@link NavWorld} port and the leg's {@link net.knightsandkings.knk.core.roads.walk.CellAccess}; no
 * engine types. Main thread: one corridor build and one bounded search per leg; the counters are for the stats line and
 * the cost check.
 */
public final class LocalLegSearch {

    /**
     * Blocks either side of the straight line that the leg's corridor covers at least; a longer leg gets half its
     * length plus 2 (a turn round a corner lies further off the line), at most {@link #MAX_HALF_WIDTH}.
     */
    public static final double HALF_WIDTH = 6.0;
    /** The widest corridor a leg gets, either side of its straight line. */
    public static final double MAX_HALF_WIDTH = 12.0;
    /** The search's cell budget: cells expanded before it gives up (the engine's search then takes the leg). */
    public static final int MAX_EXPANSIONS = 2048;
    /** Extra cost per block of clearance a cell lacks to {@code wall-clearance}. */
    public static final double CLEARANCE_COST = 5.0;
    /** The hard minimum clearance of a cell's centre: the body's half width. */
    public static final double BODY_HALF = NavGeometry.BODY_WIDTH / 2;

    private static final double SQRT2 = Math.sqrt(2);
    private static final int[] DX = {0, 1, 1, 1, 0, -1, -1, -1};
    private static final int[] DZ = {-1, -1, 0, 1, 1, 1, 0, -1};
    private static final double SAMPLE = 0.25;
    private static final double EPS = 1e-6;
    /** Blocks from a step's ends where the end cell's own clearance is enough (a leg starting beside a wall). */
    private static final double END_SLACK = 0.75;

    /** How a search ended. */
    public enum Status {
        /** A leg was found: {@link Result#waypoints()}. */
        FOUND,
        /** No standable start or target, or the reachable cells were searched without arriving. */
        NO_PATH,
        /** The cell budget ran out before the search could tell. */
        BUDGET
    }

    /**
     * @param waypoints  feet positions from the first one after the NPC to the leg's target; empty unless FOUND
     * @param pathCells  cells of the searched path before smoothing
     * @param expansions cells the search expanded
     * @param reads      world reads of the corridor build
     */
    public record Result(Status status, List<NavPoint> waypoints, int pathCells, int expansions, long reads,
                         String reason) {

        public boolean found() {
            return status == Status.FOUND;
        }
    }

    private long searches;
    private long found;
    private long expansions;
    private long nanos;
    private long reads;

    /** Searches run since start. */
    public long searches() {
        return searches;
    }

    /** Searches that found a leg. */
    public long found() {
        return found;
    }

    /** Cells expanded over all searches. */
    public long expansions() {
        return expansions;
    }

    /** Nanoseconds spent in searches, corridor builds included. */
    public long nanos() {
        return nanos;
    }

    /** World reads of the corridor builds. */
    public long reads() {
        return reads;
    }

    /**
     * Searches one leg.
     *
     * @param feet          where the NPC stands (feet)
     * @param targetFloor   the leg's target as a floor point
     * @param leg           the subject's cell access in the captured box
     * @param wallClearance the preferred margin ({@code wall-clearance})
     */
    public Result find(NavWorld world, NavPoint feet, NavPoint targetFloor, LocalLeg leg, double wallClearance) {
        long start = System.nanoTime();
        Result result = search(world, feet, targetFloor, leg, wallClearance, MAX_EXPANSIONS);
        nanos += System.nanoTime() - start;
        searches++;
        expansions += result.expansions();
        reads += result.reads();
        if (result.found()) {
            found++;
        }
        return result;
    }

    /** As {@link #find} with another cell budget (tests); counts nothing. */
    static Result search(NavWorld world, NavPoint feet, NavPoint targetFloor, LocalLeg leg, double wallClearance,
                         int budget) {
        NavPoint fromFloor = HopPlanner.floor(feet);
        NavPoint startFloor = HopPlanner.floorNear(world, fromFloor, fromFloor.blockY());
        NavPoint goalFloor = HopPlanner.floorNear(world, targetFloor, targetFloor.blockY());
        if (startFloor == null || goalFloor == null) {
            return new Result(Status.NO_PATH, List.of(), 0, 0, 0,
                startFloor == null ? "no floor under the NPC" : "no floor at the target");
        }
        NavPoint startFeet = HopPlanner.feet(startFloor);
        NavPoint goalFeet = HopPlanner.feet(goalFloor);
        double halfWidth = halfWidth(startFeet.horizontalDistance(goalFeet));
        Corridor corridor = Corridor.build(world, List.of(startFeet, goalFeet), halfWidth, true);
        Grid grid = new Grid(corridor, leg, wallClearance);
        int sx = startFeet.blockX();
        int sz = startFeet.blockZ();
        int gx = goalFeet.blockX();
        int gz = goalFeet.blockZ();
        if (corridor.floorAt(gx, gz) == Corridor.NONE || Math.abs(corridor.floorAt(gx, gz) - goalFloor.blockY()) > 1) {
            return new Result(Status.NO_PATH, List.of(), 0, 0, corridor.reads(), "the target is on another level");
        }
        if (!grid.allows(gx, gz)) {
            return new Result(Status.NO_PATH, List.of(), 0, 0, corridor.reads(), "the target is not allowed");
        }
        Search search = new Search(grid, sx, sz, gx, gz, budget, halfWidth);
        int[] path = search.run();
        if (path == null) {
            return new Result(search.exhausted ? Status.BUDGET : Status.NO_PATH, List.of(), 0, search.expanded,
                corridor.reads(), search.exhausted ? "cell budget" : "target unreachable");
        }
        List<NavPoint> waypoints = smooth(world, grid, path, feet.world(), goalFeet, targetFloor);
        return new Result(Status.FOUND, waypoints, path.length / 2, search.expanded, corridor.reads(), "found");
    }

    /** The corridor's half width for a leg of this straight length. */
    static double halfWidth(double length) {
        return Math.max(HALF_WIDTH, Math.min(MAX_HALF_WIDTH, length / 2 + 2));
    }

    // ==================== the grid ====================

    /** The corridor's cells as the search sees them. */
    static final class Grid {
        final Corridor corridor;
        final LocalLeg leg;
        final double wallClearance;

        Grid(Corridor corridor, LocalLeg leg, double wallClearance) {
            this.corridor = corridor;
            this.leg = leg;
            this.wallClearance = wallClearance;
        }

        int floor(int x, int z) {
            return corridor.floorAt(x, z);
        }

        /** A corridor cell the leg allows, with the body's room at its centre. */
        boolean allows(int x, int z) {
            int f = corridor.floorAt(x, z);
            return f != Corridor.NONE && leg.allows(x, f + 1, z) && corridor.cellClearance(x, z) >= BODY_HALF - EPS;
        }

        /** The extra cost of entering an allowed cell: the access's own and the clearance it lacks. */
        double extra(int x, int z) {
            int f = corridor.floorAt(x, z);
            double access = leg.access().extraCost(x, f + 1, z);
            double lack = Math.max(0, wallClearance - corridor.cellClearance(x, z));
            return access + CLEARANCE_COST * lack;
        }
    }

    // ==================== A* ====================

    private static final class Search {
        final Grid grid;
        final int sx;
        final int sz;
        final int gx;
        final int gz;
        final int budget;
        final int minX;
        final int minZ;
        final int size;
        final double[] g;
        final int[] parent;
        final boolean[] closed;
        int expanded;
        boolean exhausted;

        Search(Grid grid, int sx, int sz, int gx, int gz, int budget, double halfWidth) {
            this.grid = grid;
            this.sx = sx;
            this.sz = sz;
            this.gx = gx;
            this.gz = gz;
            this.budget = budget;
            int reach = (int) Math.ceil(halfWidth) + 2;
            this.minX = Math.min(sx, gx) - reach;
            this.minZ = Math.min(sz, gz) - reach;
            this.size = Math.max(Math.max(sx, gx) - minX, Math.max(sz, gz) - minZ) + reach + 1;
            this.g = new double[size * size];
            this.parent = new int[size * size];
            this.closed = new boolean[size * size];
            Arrays.fill(g, Double.POSITIVE_INFINITY);
            Arrays.fill(parent, -1);
        }

        int index(int x, int z) {
            int ix = x - minX;
            int iz = z - minZ;
            return ix < 0 || iz < 0 || ix >= size || iz >= size ? -1 : iz * size + ix;
        }

        double h(int x, int z) {
            return Math.hypot(x - gx, z - gz);
        }

        /** The path as x, z pairs from the start to the goal; null when there is none. */
        int[] run() {
            PriorityQueue<double[]> open = new PriorityQueue<>((a, b) -> {
                int c = Double.compare(a[0], b[0]);
                return c != 0 ? c : Double.compare(b[1], a[1]); // ties: the larger g first
            });
            int s = index(sx, sz);
            if (s < 0 || grid.floor(sx, sz) == Corridor.NONE) {
                return null;
            }
            g[s] = 0;
            open.add(new double[] {h(sx, sz), 0, s});
            while (!open.isEmpty()) {
                double[] e = open.poll();
                int i = (int) e[2];
                if (closed[i] || e[1] > g[i] + EPS) {
                    continue;
                }
                closed[i] = true;
                int x = minX + i % size;
                int z = minZ + i / size;
                if (x == gx && z == gz) {
                    return path(i);
                }
                if (expanded >= budget) {
                    exhausted = true;
                    return null;
                }
                expanded++;
                int f = grid.floor(x, z);
                for (int d = 0; d < 8; d++) {
                    int nx = x + DX[d];
                    int nz = z + DZ[d];
                    int n = index(nx, nz);
                    if (n < 0 || closed[n] || !step(f, nx, nz)) {
                        continue;
                    }
                    boolean diagonal = (d & 1) == 1;
                    if (diagonal && !(step(f, x + DX[d], z) && step(f, x, z + DZ[d])
                        && Math.abs(grid.floor(nx, nz) - grid.floor(x + DX[d], z)) <= 1
                        && Math.abs(grid.floor(nx, nz) - grid.floor(x, z + DZ[d])) <= 1)) {
                        continue; // no corner is cut: both flanking cells open and within a step
                    }
                    double cost = (diagonal ? SQRT2 : 1) + grid.extra(nx, nz);
                    double ng = g[i] + cost;
                    if (ng < g[n] - EPS) {
                        g[n] = ng;
                        parent[n] = i;
                        open.add(new double[] {ng + h(nx, nz), ng, n});
                    }
                }
            }
            return null;
        }

        /** Whether a body on floor {@code f} can step onto cell (nx, nz). */
        private boolean step(int f, int nx, int nz) {
            int nf = grid.floor(nx, nz);
            return nf != Corridor.NONE && Math.abs(nf - f) <= 1 && grid.allows(nx, nz);
        }

        private int[] path(int end) {
            List<Integer> chain = new ArrayList<>();
            for (int i = end; i >= 0; i = parent[i]) {
                chain.add(i);
            }
            int[] out = new int[chain.size() * 2];
            for (int k = 0; k < chain.size(); k++) {
                int i = chain.get(chain.size() - 1 - k);
                out[2 * k] = minX + i % size;
                out[2 * k + 1] = minZ + i / size;
            }
            return out;
        }
    }

    // ==================== smoothing ====================

    /**
     * The path's turns as waypoints: from each anchor, the furthest path cell a straight step reaches (see the class
     * comment). The last waypoint is the leg's own target point (its feet position).
     */
    static List<NavPoint> smooth(NavWorld world, Grid grid, int[] path, String w, NavPoint goalFeet,
                                 NavPoint targetFloor) {
        int n = path.length / 2;
        List<NavPoint> out = new ArrayList<>();
        int anchor = 0;
        while (anchor < n - 1) {
            int best = anchor + 1;
            for (int j = n - 1; j > anchor + 1; j--) {
                if (straight(world, grid, path, anchor, j, w)) {
                    best = j;
                    break;
                }
            }
            if (best < n - 1) {
                out.add(centre(grid, path, best, w));
            }
            anchor = best;
        }
        // the target itself, at the leg target's exact position when it is in the last cell
        NavPoint last = goalFeet.blockX() == targetFloor.blockX() && goalFeet.blockZ() == targetFloor.blockZ()
            ? new NavPoint(w, targetFloor.x(), goalFeet.y(), targetFloor.z()) : goalFeet;
        out.add(last);
        return out;
    }

    private static NavPoint centre(Grid grid, int[] path, int k, String w) {
        int x = path[2 * k];
        int z = path[2 * k + 1];
        return new NavPoint(w, x + 0.5, grid.floor(x, z) + 1, z + 0.5);
    }

    /** Whether the straight step between path cells {@code a} and {@code b} may replace the cells between them. */
    static boolean straight(NavWorld world, Grid grid, int[] path, int a, int b, String w) {
        NavPoint from = centre(grid, path, a, w);
        NavPoint to = centre(grid, path, b, w);
        double dx = to.x() - from.x();
        double dz = to.z() - from.z();
        double len = Math.hypot(dx, dz);
        if (len < EPS) {
            return true;
        }
        if (grid.corridor.reach(from.x(), from.z(), dx / len, dz / len, len + EPS) < len - EPS) {
            return false;
        }
        // the least clearance of the cells it replaces (at most wall-clearance); near its two ends, the ends' own
        double need = grid.wallClearance;
        for (int k = a + 1; k < b; k++) {
            need = Math.min(need, grid.corridor.cellClearance(path[2 * k], path[2 * k + 1]));
        }
        double needFrom = Math.max(BODY_HALF, Math.min(need, grid.corridor.cellClearance(path[2 * a], path[2 * a + 1])));
        double needTo = Math.max(BODY_HALF, Math.min(need, grid.corridor.cellClearance(path[2 * b], path[2 * b + 1])));
        need = Math.max(need, BODY_HALF);
        int samples = Math.max(1, (int) Math.ceil(len / SAMPLE));
        for (int i = 0; i <= samples; i++) {
            double t = (double) i / samples;
            double x = from.x() + dx * t;
            double z = from.z() + dz * t;
            double s = len * t;
            double here = s <= END_SLACK ? needFrom : s >= len - END_SLACK ? needTo : need;
            if (grid.corridor.clearanceAt(x, z, here) < here - EPS) {
                return false;
            }
            for (int c = 0; c < 4; c++) {
                int bx = (int) Math.floor(x + ((c & 1) == 0 ? -BODY_HALF : BODY_HALF));
                int bz = (int) Math.floor(z + ((c & 2) == 0 ? -BODY_HALF : BODY_HALF));
                if (!grid.allows(bx, bz)) {
                    return false;
                }
            }
        }
        return NavGeometry.straightWalkable(world, from, to);
    }
}

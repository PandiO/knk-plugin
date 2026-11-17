package net.knightsandkings.knk.core.navigation.shape;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Plaza lines (implementation plan 4c.3 decision 6, link M5; BEHAVIOR_MODEL NAV-27): the line across a plaza from where
 * the route enters it to where it leaves, on the {@link Corridor}'s clearance map, instead of the road graph's V
 * through the plaza junction (the builder collapses a plaza into one junction, plan 4c.1 item 6).
 *
 * <p>A bounded A* over the corridor's cells inside the plaza's footprint (8 directions, a diagonal only past two open
 * flanking cells within a step), each cell costing its length plus {@link #CLEARANCE_COST} per block of clearance it
 * lacks to the wanted clearance and never entered under {@link CorridorShaper#HARD_MINIMUM}; then string pulling by
 * line of sight on the clearance map (Theta*-style, research report § 3.9): from each anchor the furthest path point
 * whose straight step stays on the plaza with floor continuity, keeps the body's corners in the corridor and keeps the
 * least clearance of the points it replaces (at most the wanted clearance). On an open plaza the result is the chord;
 * an obstacle on the chord (a well, a stall) is passed with the clearance; a region the line does not pass is an
 * obstacle of the corridor, so the line keeps out of it. Pure; main thread.
 */
public final class PlazaLine {

    /** Rays the open floor round a junction is measured along ({@link #openRadius}). */
    static final int RAYS = 16;
    /** The open radius is the {@code RAY_RANK + 1}-th shortest ray: a well or a post by the junction blocks a few. */
    static final int RAY_RANK = 3;
    /** Cost per block of clearance a cell lacks to the wanted clearance. */
    static final double CLEARANCE_COST = 10;
    /** Cells expanded at most. */
    public static final int CELL_BUDGET = 4096;
    private static final double SLACK = 0.05;
    private static final double SAMPLE = 0.25;
    private static final double HALF_BODY = 0.3;

    private PlazaLine() {
    }

    /** A point of the line: its position and its floor. */
    public record Point(double x, double z, int floor) {
    }

    /** What a search found: the line ({@code null} when none) and the cells it expanded. */
    public record Result(List<Point> line, int expanded) {
        public boolean found() {
            return line != null;
        }
    }

    /**
     * The open floor round a junction: the {@link #RAY_RANK}+1-th shortest of {@link #RAYS} rays from it, each at most
     * {@code max}. A junction of narrow streets measures about the streets' half width; a plaza its half size. Read
     * straight from the world before any corridor is built (so a junction of streets costs a few rays, not a
     * plaza-sized corridor): each ray walks cell by cell
     * from {@code feet} while the next cell has a standable floor within a step of the last, the body's headroom and
     * the same WorldGuard regions as {@code feet}'s cell. {@code reads[0]} counts the world reads.
     */
    public static double openRadius(net.knightsandkings.knk.core.navigation.shape.NavWorld world,
                                    net.knightsandkings.knk.core.navigation.shape.NavPoint feet, double max, long[] reads) {
        String w = feet.world();
        java.util.Set<String> regions = world.regionsAt(w, feet.blockX(), feet.blockY(), feet.blockZ());
        reads[0]++;
        double[] rays = new double[RAYS];
        for (int k = 0; k < RAYS; k++) {
            double a = 2 * Math.PI * k / RAYS;
            double dx = Math.cos(a);
            double dz = Math.sin(a);
            int floor = feet.blockY() - 1;
            int cx = feet.blockX();
            int cz = feet.blockZ();
            double t = 0;
            double reached = max;
            while (t < max) {
                t = Math.min(max, t + SAMPLE);
                int nx = (int) Math.floor(feet.x() + dx * t);
                int nz = (int) Math.floor(feet.z() + dz * t);
                if (nx == cx && nz == cz) {
                    continue;
                }
                int found = Integer.MIN_VALUE;
                reads[0]++;
                if (world.isLoaded(w, nx, nz)) {
                    for (int d : new int[] {0, 1, -1}) {
                        reads[0] += 2;
                        if (world.isStandableFloor(w, nx, floor + d, nz)
                            && world.freeHeight(w, nx, floor + d, nz) >= net.knightsandkings.knk.core.navigation.shape.NavGeometry.BODY_HEIGHT) {
                            found = floor + d;
                            break;
                        }
                    }
                }
                if (found != Integer.MIN_VALUE) {
                    reads[0]++;
                    if (!world.regionsAt(w, nx, found + 1, nz).equals(regions)) {
                        found = Integer.MIN_VALUE;
                    }
                }
                if (found == Integer.MIN_VALUE) {
                    reached = Math.max(0, t - SAMPLE);
                    break;
                }
                floor = found;
                cx = nx;
                cz = nz;
            }
            rays[k] = reached;
        }
        Arrays.sort(rays);
        return rays[RAY_RANK];
    }

    /**
     * The line from {@code start} to {@code goal} across the plaza {@code area} (both ends are on the route, inside
     * or on the edge of the area), keeping {@code clearance} where the plaza allows.
     */
    public static Result search(Corridor corridor, Point start, Point goal, Corridor.Disc area, double clearance) {
        int sx = (int) Math.floor(start.x());
        int sz = (int) Math.floor(start.z());
        int gx = (int) Math.floor(goal.x());
        int gz = (int) Math.floor(goal.z());
        if (!corridor.contains(sx, sz) || !corridor.contains(gx, gz)) {
            return new Result(null, 0);
        }
        Grid grid = new Grid(corridor, area, sx, sz, gx, gz);
        long startKey = key(sx, sz);
        long goalKey = key(gx, gz);
        Map<Long, Double> cost = new HashMap<>();
        Map<Long, Long> parent = new HashMap<>();
        PriorityQueue<double[]> open = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        cost.put(startKey, 0.0);
        open.add(new double[] {h(sx, sz, gx, gz), sx, sz, 0});
        int expanded = 0;
        boolean reached = false;
        while (!open.isEmpty()) {
            double[] top = open.poll();
            int x = (int) top[1];
            int z = (int) top[2];
            long k = key(x, z);
            if (top[3] > cost.getOrDefault(k, Double.MAX_VALUE) + 1e-9) {
                continue;
            }
            if (k == goalKey) {
                reached = true;
                break;
            }
            if (++expanded > CELL_BUDGET) {
                break;
            }
            int floor = corridor.floorAt(x, z);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    int nx = x + dx;
                    int nz = z + dz;
                    if (!grid.allowed(nx, nz) || Math.abs(corridor.floorAt(nx, nz) - floor) > 1) {
                        continue;
                    }
                    if (dx != 0 && dz != 0 && (!grid.allowed(x + dx, z) || !grid.allowed(x, z + dz)
                        || Math.abs(corridor.floorAt(x + dx, z) - floor) > 1
                        || Math.abs(corridor.floorAt(x, z + dz) - floor) > 1)) {
                        continue;
                    }
                    double lack = Math.max(0, clearance - corridor.cellClearance(nx, nz));
                    double c = top[3] + (dx != 0 && dz != 0 ? Math.sqrt(2) : 1) + CLEARANCE_COST * lack;
                    long nk = key(nx, nz);
                    if (c < cost.getOrDefault(nk, Double.MAX_VALUE) - 1e-9) {
                        cost.put(nk, c);
                        parent.put(nk, k);
                        open.add(new double[] {c + h(nx, nz, gx, gz), nx, nz, c});
                    }
                }
            }
        }
        if (!reached) {
            return new Result(null, expanded);
        }
        List<Point> cells = new ArrayList<>();
        for (long k = goalKey; ; k = parent.get(k)) {
            int x = (int) (k >> 32);
            int z = (int) k;
            cells.add(0, new Point(x + 0.5, z + 0.5, corridor.floorAt(x, z)));
            if (k == startKey) {
                break;
            }
        }
        // the ends are the route's own points, not their cells' centres
        cells.set(0, new Point(start.x(), start.z(), corridor.floorAt(sx, sz)));
        if (cells.size() == 1) {
            cells.add(new Point(goal.x(), goal.z(), corridor.floorAt(gx, gz)));
        } else {
            cells.set(cells.size() - 1, new Point(goal.x(), goal.z(), corridor.floorAt(gx, gz)));
        }
        return new Result(refine(grid, pull(grid, cells, clearance), clearance), expanded);
    }

    private static double h(int x, int z, int gx, int gz) {
        return Math.hypot(gx - x, gz - z);
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    /**
     * String pulling over the path's points by line of sight: gallop then halve from each anchor. A step must keep the
     * least clearance of the path points it replaces (at most the wanted clearance), less only near its own ends
     * ({@link Grid#sees}).
     */
    private static List<Point> pull(Grid grid, List<Point> path, double clearance) {
        int n = path.size();
        double[] clear = new double[n];
        for (int k = 0; k < n; k++) {
            clear[k] = clearOf(grid, path.get(k), clearance);
        }
        List<Point> out = new ArrayList<>();
        out.add(path.get(0));
        int a = 0;
        double[] inner = new double[n]; // the least clearance of the points strictly between a and k
        while (a < n - 1) {
            inner[a] = clearance;
            if (a + 1 < n) {
                inner[a + 1] = clearance;
            }
            for (int k = a + 2; k < n; k++) {
                inner[k] = Math.min(inner[k - 1], clear[k - 1]);
            }
            int good = a + 1;
            int step = 1;
            int bad = -1;
            while (good < n - 1) {
                int probe = Math.min(n - 1, good + step);
                if (grid.sees(path.get(a), path.get(probe), inner[probe])) {
                    good = probe;
                    step *= 2;
                } else {
                    bad = probe;
                    break;
                }
            }
            while (bad > good + 1) {
                int mid = (good + bad) >>> 1;
                if (grid.sees(path.get(a), path.get(mid), inner[mid])) {
                    good = mid;
                } else {
                    bad = mid;
                }
            }
            out.add(path.get(good));
            a = good;
        }
        return out;
    }

    /** Passes of {@link #refine}. */
    static final int REFINE_PASSES = 6;

    /**
     * Moves each bend of the string-pulled line to shorten it while both its steps still fit: towards the straight line
     * between its neighbours, or towards either neighbour, as far as fits (halving); a bend whose neighbours see each
     * other goes. Over a few passes the bends slide from cell centres to the tangent points round an obstacle's
     * clearance (the funnel's answer, research report § 3.9).
     */
    private static List<Point> refine(Grid grid, List<Point> line, double clearance) {
        List<Point> p = new ArrayList<>(line);
        for (int pass = 0; pass < REFINE_PASSES && p.size() > 2; pass++) {
            boolean moved = false;
            for (int i = 1; i < p.size() - 1; i++) {
                Point a = p.get(i - 1);
                Point b = p.get(i + 1);
                Point c = p.get(i);
                double cap = Math.min(clearance, clearOf(grid, c, clearance));
                if (grid.sees(a, b, cap)) {
                    p.remove(i);
                    i--;
                    moved = true;
                    continue;
                }
                double dx = b.x() - a.x();
                double dz = b.z() - a.z();
                double len2 = dx * dx + dz * dz;
                double t = len2 < 1e-12 ? 0
                    : Math.max(0, Math.min(1, ((c.x() - a.x()) * dx + (c.z() - a.z()) * dz) / len2));
                double[][] towards = {{a.x() + dx * t, a.z() + dz * t, 1}, {a.x(), a.z(), 0.5}, {b.x(), b.z(), 0.5}};
                Point best = c;
                double bestLen = length(a, c) + length(c, b);
                for (double[] to : towards) {
                    double lo = 0;
                    double hi = to[2];
                    Point fit = null;
                    for (int k = 0; k < 8; k++) {
                        double s = (lo + hi) / 2;
                        Point m = at(grid, c.x() + (to[0] - c.x()) * s, c.z() + (to[1] - c.z()) * s);
                        if (m != null && grid.sees(a, m, cap) && grid.sees(m, b, cap)) {
                            lo = s;
                            fit = m;
                        } else {
                            hi = s;
                        }
                    }
                    if (fit != null && length(a, fit) + length(fit, b) < bestLen - 1e-6) {
                        best = fit;
                        bestLen = length(a, fit) + length(fit, b);
                    }
                }
                if (best != c) {
                    p.set(i, best);
                    moved = true;
                }
            }
            if (!moved) {
                break;
            }
        }
        return p;
    }

    private static double length(Point a, Point b) {
        return Math.hypot(b.x() - a.x(), b.z() - a.z());
    }

    private static double clearOf(Grid grid, Point p, double cap) {
        return grid.corridor.clearanceAt(p.x(), p.z(), cap);
    }

    /** A point on the plaza with its cell's floor, or null off it. */
    private static Point at(Grid grid, double x, double z) {
        int cx = (int) Math.floor(x);
        int cz = (int) Math.floor(z);
        return grid.onPlaza(cx, cz) ? new Point(x, z, grid.corridor.floorAt(cx, cz)) : null;
    }

    /** The cells a plaza line may use: the corridor's, inside the plaza's footprint, the two ends' cells too. */
    private static final class Grid {
        final Corridor corridor;
        final Corridor.Disc area;
        final int sx;
        final int sz;
        final int gx;
        final int gz;

        Grid(Corridor corridor, Corridor.Disc area, int sx, int sz, int gx, int gz) {
            this.corridor = corridor;
            this.area = area;
            this.sx = sx;
            this.sz = sz;
            this.gx = gx;
            this.gz = gz;
        }

        boolean end(int x, int z) {
            return (x == sx && z == sz) || (x == gx && z == gz);
        }

        boolean allowed(int x, int z) {
            if (!corridor.contains(x, z)) {
                return false;
            }
            if (end(x, z)) {
                return true;
            }
            return area.holds(x + 0.5, z + 0.5) && corridor.cellClearance(x, z) >= CorridorShaper.HARD_MINIMUM;
        }

        /** In the corridor and on the plaza (or an end's cell), whatever its clearance (the step check judges that). */
        boolean onPlaza(int x, int z) {
            return corridor.contains(x, z) && (end(x, z) || area.holds(x + 0.5, z + 0.5));
        }

        /**
         * Whether the straight step a→b stays on the plaza with floor continuity, the body's corners in the corridor,
         * and at every sample at least {@code cap} clearance, less only near an end that has less itself (by the
         * sample's distance from that end): a line leaving a narrow road opening widens its margin as it goes.
         */
        boolean sees(Point a, Point b, double cap) {
            double dx = b.x() - a.x();
            double dz = b.z() - a.z();
            double len = Math.hypot(dx, dz);
            double ca = corridor.clearanceAt(a.x(), a.z(), cap);
            double cb = corridor.clearanceAt(b.x(), b.z(), cap);
            int samples = Math.max(1, (int) Math.ceil(len / SAMPLE));
            int prev = a.floor();
            for (int i = 1; i < samples; i++) {
                double t = (double) i / samples;
                double x = a.x() + dx * t;
                double z = a.z() + dz * t;
                int cx = (int) Math.floor(x);
                int cz = (int) Math.floor(z);
                if (!onPlaza(cx, cz)) {
                    return false;
                }
                int f = corridor.floorAt(cx, cz);
                if (Math.abs(f - prev) > 1) {
                    return false;
                }
                for (int c = 0; c < 4; c++) {
                    int bx = (int) Math.floor(x + ((c & 1) == 0 ? -HALF_BODY : HALF_BODY));
                    int bz = (int) Math.floor(z + ((c & 2) == 0 ? -HALF_BODY : HALF_BODY));
                    int bf = corridor.floorAt(bx, bz);
                    if (bf == Corridor.NONE || Math.abs(bf - f) > 1) {
                        return false;
                    }
                }
                double minClear = Math.min(cap, Math.min(ca + len * t, cb + len * (1 - t))) - SLACK;
                if (minClear > 0 && corridor.clearanceAt(x, z, minClear + SLACK) < minClear) {
                    return false;
                }
                prev = f;
            }
            return true;
        }
    }
}

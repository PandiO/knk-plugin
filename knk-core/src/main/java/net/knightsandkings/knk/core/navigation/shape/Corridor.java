package net.knightsandkings.knk.core.navigation.shape;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The walkable corridor round one piece of a route line (implementation plan 4c.3 decision 1, link M2; BEHAVIOR_MODEL
 * NAV-24): the cells within {@code corridor-half-width} blocks of the line where a body can stand, each with its floor,
 * reached from the line's own cells with floor continuity (a step of at most one block from cell to cell, the body's
 * headroom) and in a WorldGuard region set one of the line's points has. Everything else inside the strip (a wall, a
 * fence, a post, a drop, a cell of another level, a region the line never enters) is an obstacle; cells beyond the
 * strip are not looked at and count as neither.
 *
 * <p>The clearance of a point is its distance to the nearest obstacle cell's square, capped at {@link #CAP}: a capped
 * Euclidean distance transform, evaluated per cell centre ({@link #cellClearance}, kept once worked out) and for any
 * point ({@link #clearanceAt}). An obstacle is judged against the floor of the point's own cell: a cell
 * whose floor is more than a step above or below is a wall or a drop for it, so a corridor over a staircase works.
 *
 * <p>Built once per route stretch and shared by every NPC on it ({@link CorridorShaper}); its cells are fixed after the
 * build, it reads the world only while it is built, and the clearance of a cell's centre is kept once asked. Later
 * links read it: M3 the free width per path point ({@link #freeWidth}), M6 the clearance per cell
 * ({@link #cellClearance}, {@link #clearanceAt}). Pure, over the {@link NavWorld} port; main thread.
 */
public final class Corridor {

    /** The largest clearance worked out; further walls do not matter for any decided value (0.8 preferred). */
    public static final double CAP = 2.0;
    /** {@link #floorAt} for a cell that is not in the corridor. */
    public static final int NONE = Integer.MIN_VALUE;
    /** A cell inside the strip that a body cannot reach from the line: an obstacle. */
    private static final int BLOCKED = Integer.MIN_VALUE + 1;
    /** A cell beyond the strip: not looked at. */
    private static final int OUTSIDE = Integer.MIN_VALUE + 2;
    /** Cells looked at round a point for its clearance: enough to reach {@link #CAP} from anywhere in a cell. */
    private static final int REACH = (int) Math.ceil(CAP) + 1;

    private final String world;
    private final int minX;
    private final int minZ;
    private final int sizeX;
    private final int sizeZ;
    /** Per cell: its floor, or {@link #BLOCKED} or {@link #OUTSIDE}. */
    private final int[] floors;
    /** Per cell: the clearance of its centre (0 for cells not in the corridor). */
    /** Per cell: the clearance of its centre, NaN until first asked. */
    private final float[] clearance;
    private final double halfWidth;
    private final int cells;
    private final long reads;

    private Corridor(String world, int minX, int minZ, int sizeX, int sizeZ, int[] floors, double halfWidth, long reads) {
        this.world = world;
        this.minX = minX;
        this.minZ = minZ;
        this.sizeX = sizeX;
        this.sizeZ = sizeZ;
        this.floors = floors;
        this.halfWidth = halfWidth;
        this.reads = reads;
        int walkable = 0;
        for (int f : floors) {
            if (f > OUTSIDE) {
                walkable++;
            }
        }
        this.clearance = new float[floors.length];
        java.util.Arrays.fill(clearance, Float.NaN); // worked out when first asked
        this.cells = walkable;
    }

    /**
     * The corridor round a line of feet positions (each on a standable floor, the first one's cell is the corridor's
     * root): the cells within {@code halfWidth} blocks of the line, flooded from the line's cells.
     */
    public static Corridor build(NavWorld world, List<NavPoint> feet, double halfWidth) {
        return build(world, feet, halfWidth, false);
    }

    /**
     * As {@link #build(NavWorld, List, double)}; with {@code anyRegion} a cell's WorldGuard regions don't matter (link
     * M6: a local leg's own cell access decides where the NPC may go, so the corridor only gives the walk and its
     * clearance).
     */
    public static Corridor build(NavWorld world, List<NavPoint> feet, double halfWidth, boolean anyRegion) {
        return build(world, feet, halfWidth, anyRegion, List.of());
    }

    /**
     * A round area the corridor also covers (link M5: a plaza's footprint round its junction, plan 4c.3 decision 6).
     *
     * @param x      the centre's x
     * @param z      the centre's z
     * @param radius blocks from the centre
     */
    public record Disc(double x, double z, double radius) {
        boolean holds(double px, double pz) {
            return Math.hypot(px - x, pz - z) <= radius;
        }
    }

    /**
     * As {@link #build(NavWorld, List, double, boolean)}; the strip also covers {@code discs} (link M5: the corridor
     * widens to a plaza's footprint). Their cells are flooded from the line like the others, with the same floor and
     * region rules.
     */
    public static Corridor build(NavWorld world, List<NavPoint> feet, double halfWidth, boolean anyRegion,
                                 List<Disc> discs) {
        if (feet.isEmpty()) {
            throw new IllegalArgumentException("a corridor needs a line");
        }
        String w = feet.get(0).world();
        double minPX = Double.MAX_VALUE;
        double minPZ = Double.MAX_VALUE;
        double maxPX = -Double.MAX_VALUE;
        double maxPZ = -Double.MAX_VALUE;
        for (NavPoint p : feet) {
            minPX = Math.min(minPX, p.x());
            minPZ = Math.min(minPZ, p.z());
            maxPX = Math.max(maxPX, p.x());
            maxPZ = Math.max(maxPZ, p.z());
        }
        int reach = (int) Math.ceil(halfWidth) + 1;
        int minX = (int) Math.floor(minPX) - reach;
        int minZ = (int) Math.floor(minPZ) - reach;
        int maxX = (int) Math.floor(maxPX) + reach;
        int maxZ = (int) Math.floor(maxPZ) + reach;
        for (Disc d : discs) {
            int r = (int) Math.ceil(d.radius()) + 1;
            minX = Math.min(minX, (int) Math.floor(d.x()) - r);
            minZ = Math.min(minZ, (int) Math.floor(d.z()) - r);
            maxX = Math.max(maxX, (int) Math.floor(d.x()) + r);
            maxZ = Math.max(maxZ, (int) Math.floor(d.z()) + r);
        }
        int sizeX = maxX - minX + 1;
        int sizeZ = maxZ - minZ + 1;
        int[] floors = new int[sizeX * sizeZ];
        for (int i = 0; i < floors.length; i++) {
            int x = minX + i % sizeX;
            int z = minZ + i / sizeX;
            boolean in = withinOf(feet, x + 0.5, z + 0.5, halfWidth);
            for (int k = 0; !in && k < discs.size(); k++) {
                in = discs.get(k).holds(x + 0.5, z + 0.5);
            }
            floors[i] = in ? BLOCKED : OUTSIDE;
        }
        CountingReads reads = new CountingReads(world);
        Set<Set<String>> regionSets = new HashSet<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (NavPoint p : feet) {
            int i = (p.blockZ() - minZ) * sizeX + (p.blockX() - minX);
            int floorY = p.blockY() - 1;
            if (!anyRegion) {
                regionSets.add(reads.regionsAt(w, p.blockX(), p.blockY(), p.blockZ()));
            }
            if (floors[i] == BLOCKED || floors[i] == OUTSIDE) {
                floors[i] = floorY; // the line's own cell (a seed), whatever the strip test says
                queue.add(i);
            }
        }
        int[][] moves = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!queue.isEmpty()) {
            int i = queue.poll();
            int x = minX + i % sizeX;
            int z = minZ + i / sizeX;
            int floor = floors[i];
            for (int[] m : moves) {
                int nx = x + m[0];
                int nz = z + m[1];
                if (nx < minX || nz < minZ || nx >= minX + sizeX || nz >= minZ + sizeZ) {
                    continue;
                }
                int n = (nz - minZ) * sizeX + (nx - minX);
                if (floors[n] != BLOCKED || !reads.isLoaded(w, nx, nz)) {
                    continue;
                }
                for (int d : new int[] {0, 1, -1}) {
                    int f = floor + d;
                    if (reads.isStandableFloor(w, nx, f, nz) && reads.freeHeight(w, nx, f, nz) >= NavGeometry.BODY_HEIGHT
                        && (anyRegion || regionSets.contains(reads.regionsAt(w, nx, f + 1, nz)))) {
                        floors[n] = f;
                        queue.add(n);
                        break;
                    }
                }
            }
        }
        return new Corridor(w, minX, minZ, sizeX, sizeZ, floors, halfWidth, reads.count);
    }

    /** Whether a point is within {@code r} of the line (horizontally); pieces far away are skipped by their box. */
    static boolean withinOf(List<NavPoint> line, double px, double pz, double r) {
        if (line.size() == 1) {
            return Math.hypot(px - line.get(0).x(), pz - line.get(0).z()) <= r;
        }
        for (int k = 1; k < line.size(); k++) {
            NavPoint a = line.get(k - 1);
            NavPoint b = line.get(k);
            if (px < Math.min(a.x(), b.x()) - r || px > Math.max(a.x(), b.x()) + r
                || pz < Math.min(a.z(), b.z()) - r || pz > Math.max(a.z(), b.z()) + r) {
                continue;
            }
            if (distanceToLine(List.of(a, b), px, pz) <= r) {
                return true;
            }
        }
        return false;
    }

    /** The horizontal distance from a point to the nearest piece of the line. */
    static double distanceToLine(List<NavPoint> line, double px, double pz) {
        if (line.size() == 1) {
            return Math.hypot(px - line.get(0).x(), pz - line.get(0).z());
        }
        double best = Double.MAX_VALUE;
        for (int k = 1; k < line.size(); k++) {
            NavPoint a = line.get(k - 1);
            NavPoint b = line.get(k);
            double dx = b.x() - a.x();
            double dz = b.z() - a.z();
            double len2 = dx * dx + dz * dz;
            double t = len2 < 1e-12 ? 0 : Math.max(0, Math.min(1, ((px - a.x()) * dx + (pz - a.z()) * dz) / len2));
            best = Math.min(best, Math.hypot(px - (a.x() + t * dx), pz - (a.z() + t * dz)));
        }
        return best;
    }

    /** The world this corridor is in. */
    public String world() {
        return world;
    }

    /** The decided {@code corridor-half-width} it was built with. */
    public double halfWidth() {
        return halfWidth;
    }

    /** Walkable cells (the corridor's size). */
    public int cells() {
        return cells;
    }

    /** World reads made while it was built (the cost test, plan 4c.3 decision 1.5). */
    public long reads() {
        return reads;
    }

    /** The floor of a cell, or {@link #NONE} when the cell is not in the corridor. */
    public int floorAt(int x, int z) {
        int i = index(x, z);
        return i < 0 || floors[i] <= OUTSIDE ? NONE : floors[i];
    }

    /** Whether the cell is in the corridor. */
    public boolean contains(int x, int z) {
        return floorAt(x, z) != NONE;
    }

    /** The clearance of a cell's centre (0 for a cell not in the corridor), from the build's distance transform. */
    public double cellClearance(int x, int z) {
        int i = index(x, z);
        if (i < 0 || floors[i] <= OUTSIDE) {
            return 0;
        }
        if (Float.isNaN(clearance[i])) {
            clearance[i] = (float) scan(x + 0.5, z + 0.5, x, z, floors[i], CAP);
        }
        return clearance[i];
    }

    /**
     * The clearance of a point (its distance to the nearest obstacle's square, at most {@link #CAP}) judged from the
     * floor of its own cell; 0 when its cell is not in the corridor.
     */
    public double clearanceAt(double x, double z) {
        return clearanceAt(x, z, CAP);
    }

    /**
     * As {@link #clearanceAt(double, double)}, worked out only up to {@code cap} (at most {@link #CAP}): a point with
     * at least that much gets {@code cap}. Cheaper for the small caps the shaper asks for.
     */
    public double clearanceAt(double x, double z, double cap) {
        int cx = (int) Math.floor(x);
        int cz = (int) Math.floor(z);
        int i = index(cx, cz);
        if (i < 0 || floors[i] <= OUTSIDE) {
            return 0;
        }
        cap = Math.min(cap, CAP);
        // a point is at most half a cell's diagonal from its cell's centre
        if (!Float.isNaN(clearance[i]) && clearance[i] - HALF_DIAGONAL >= cap) {
            return cap;
        }
        return scan(x, z, cx, cz, floors[i], cap);
    }

    private static final double HALF_DIAGONAL = Math.sqrt(0.5);

    /** The clearance of a point in cell (cx, cz) on {@code floor}, up to {@code cap}, by looking at the cells round it. */
    private double scan(double x, double z, int cx, int cz, int floor, double cap) {
        int reach = Math.min(REACH, (int) Math.ceil(cap) + 1);
        double best = cap;
        for (int dz = -reach; dz <= reach; dz++) {
            for (int dx = -reach; dx <= reach; dx++) {
                int ox = cx + dx;
                int oz = cz + dz;
                if (!obstacleFor(ox, oz, floor)) {
                    continue;
                }
                double ddx = Math.max(Math.max(ox - x, 0), x - (ox + 1));
                double ddz = Math.max(Math.max(oz - z, 0), z - (oz + 1));
                double d = Math.hypot(ddx, ddz);
                if (d < best) {
                    best = d;
                }
            }
        }
        return best;
    }

    /**
     * Whether a cell stops a body standing on {@code floor} next to it: inside the strip and not walkable, or walkable
     * more than a step above or below. Cells beyond the strip are not obstacles (not looked at).
     */
    private boolean obstacleFor(int x, int z, int floor) {
        int i = index(x, z);
        if (i < 0) {
            return false;
        }
        int f = floors[i];
        if (f == OUTSIDE) {
            return false;
        }
        return f == BLOCKED || Math.abs(f - floor) > 1;
    }

    /**
     * The free width across a heading at a feet position (the M3 fit, plan 4c.3 decision 2.1): how far the body's
     * centre can move to the left and to the right, square to {@code (hx, hz)}, before it leaves the corridor or meets
     * a step of more than a block, each side at most the half width, to the exact block face.
     */
    public FreeWidth freeWidth(NavPoint feet, double hx, double hz) {
        double len = Math.hypot(hx, hz);
        if (len < 1e-9) {
            hx = 1;
            hz = 0;
        } else {
            hx /= len;
            hz /= len;
        }
        if (floorAt(feet.blockX(), feet.blockZ()) == NONE) {
            return new FreeWidth(0, 0);
        }
        // right of heading (hx, hz) is (-hz, hx), as in FreeWidth and the group's lanes
        double right = reach(feet.x(), feet.z(), -hz, hx, halfWidth);
        double left = reach(feet.x(), feet.z(), hz, -hx, halfWidth);
        return new FreeWidth(left, right);
    }

    /** Spacing of the places across the line that the shaper looks at. */
    public static final double LATERAL_STEP = 0.1;

    /**
     * How far from {@code (x, z)} along the unit direction {@code (dx, dz)} a body's centre stays in the corridor with
     * floor continuity (a step of at most one block from cell to cell), to the exact block face where it leaves, at
     * most {@code max}.
     */
    public double reach(double x, double z, double dx, double dz, double max) {
        int cx = (int) Math.floor(x);
        int cz = (int) Math.floor(z);
        int floor = floorAt(cx, cz);
        if (floor == NONE) {
            return 0;
        }
        int stepX = dx > 1e-12 ? 1 : dx < -1e-12 ? -1 : 0;
        int stepZ = dz > 1e-12 ? 1 : dz < -1e-12 ? -1 : 0;
        double tMaxX = stepX == 0 ? Double.MAX_VALUE : (stepX > 0 ? cx + 1 - x : x - cx) / Math.abs(dx);
        double tMaxZ = stepZ == 0 ? Double.MAX_VALUE : (stepZ > 0 ? cz + 1 - z : z - cz) / Math.abs(dz);
        double tDeltaX = stepX == 0 ? Double.MAX_VALUE : 1 / Math.abs(dx);
        double tDeltaZ = stepZ == 0 ? Double.MAX_VALUE : 1 / Math.abs(dz);
        while (true) {
            double t;
            if (Math.abs(tMaxX - tMaxZ) < 1e-12) {
                // through a corner: both neighbours must be open, then the diagonal cell
                t = tMaxX;
                if (t >= max) {
                    return max;
                }
                int fx = floorAt(cx + stepX, cz);
                int fz = floorAt(cx, cz + stepZ);
                if (fx == NONE || fz == NONE || Math.abs(fx - floor) > 1 || Math.abs(fz - floor) > 1) {
                    return t;
                }
                cx += stepX;
                cz += stepZ;
                tMaxX += tDeltaX;
                tMaxZ += tDeltaZ;
            } else if (tMaxX < tMaxZ) {
                t = tMaxX;
                cx += stepX;
                tMaxX += tDeltaX;
            } else {
                t = tMaxZ;
                cz += stepZ;
                tMaxZ += tDeltaZ;
            }
            if (t >= max) {
                return max;
            }
            int f = floorAt(cx, cz);
            if (f == NONE || Math.abs(f - floor) > 1) {
                return t;
            }
            floor = f;
        }
    }

    /**
     * The narrowest free width through a point over directions every 15° (a doorway's width, whatever way the line
     * crosses it), each side at most {@code max}; with the direction it was found in.
     *
     * @return {width, direction x, direction z}
     */
    public double[] narrowest(double x, double z, double max) {
        double best = Double.MAX_VALUE;
        double bx = 1;
        double bz = 0;
        for (int deg = 0; deg < 180; deg += 15) {
            double a = Math.toRadians(deg);
            double dx = Math.cos(a);
            double dz = Math.sin(a);
            double width = reach(x, z, dx, dz, max) + reach(x, z, -dx, -dz, max);
            if (width < best - 1e-9) {
                best = width;
                bx = dx;
                bz = dz;
            }
        }
        return new double[] {best, bx, bz};
    }

    private int index(int x, int z) {
        int ix = x - minX;
        int iz = z - minZ;
        if (ix < 0 || iz < 0 || ix >= sizeX || iz >= sizeZ) {
            return -1;
        }
        return iz * sizeX + ix;
    }

    /** Counts the world reads of a build (the cost test). */
    private static final class CountingReads {
        private final NavWorld world;
        long count;

        CountingReads(NavWorld world) {
            this.world = world;
        }

        boolean isLoaded(String w, int x, int z) {
            count++;
            return world.isLoaded(w, x, z);
        }

        boolean isStandableFloor(String w, int x, int y, int z) {
            count++;
            return world.isStandableFloor(w, x, y, z);
        }

        double freeHeight(String w, int x, int y, int z) {
            count++;
            return world.freeHeight(w, x, y, z);
        }

        Set<String> regionsAt(String w, int x, int y, int z) {
            count++;
            return world.regionsAt(w, x, y, z);
        }
    }
}

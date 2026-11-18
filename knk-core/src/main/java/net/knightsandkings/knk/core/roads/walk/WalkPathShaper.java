package net.knightsandkings.knk.core.roads.walk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

import net.knightsandkings.knk.core.navigation.shape.Corridor;
import net.knightsandkings.knk.core.navigation.shape.CorridorShaper;
import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.util.BlockKey;

/**
 * Wide, smooth corners on walk trails (off-road navigation phase 6.1, PandiO/knk-workspace#710, DESIGN § 7.1, decisions
 * O07, O11): a found walk path is shaped by the shared {@link CorridorShaper} over the captured terrain
 * ({@link WalkTerrainNavWorld}) and then drawn as a curve whose samples are checked against the clearance map. Runs on
 * the walk thread, after the search, from the request's terrain only; never reads the live world.
 *
 * <ol>
 *     <li><b>Runs.</b> The path is cut into runs of standing cells joined by steps of at most one block up or down; a
 *     ladder cell and a drop end a run and stay as they are (the shaper's corridor is one floor per column, a climb is
 *     no corridor).</li>
 *     <li><b>Shaping.</b> Each run of two cells or more goes through {@link CorridorShaper} with
 *     {@code wall-clearance} (hard minimum {@link CorridorShaper#HARD_MINIMUM}) in a strip of {@link Options#halfWidth}
 *     blocks: points pulled to the nearest place that keeps the clearance, chokepoints (doors) on their axis, string
 *     pulling. The run's last cell stays (the target, or the cell before a ladder or a drop); a run after a ladder or a
 *     drop starts at its own first cell again, so the joins stay where the search put them.</li>
 *     <li><b>Curve</b> ({@link Options#curve}). Each corner of a run's shaped line is rounded by a quadratic Bézier from
 *     a point up to {@link #CORNER_REACH} blocks before the corner to one as far after it (at most half of either
 *     side), drawn through a sample every {@link #CURVE_SAMPLE} blocks. Every point of the drawn curve, checked every
 *     {@link #CHECK} blocks, must lie on a corridor floor within a step of its height and keep the clearance the shaped
 *     line has round that corner (at most {@code wall-clearance}); a corner whose
 *     curve fails is tried again half as wide, and kept sharp (the shaped line itself) below {@link #MIN_CORNER}.
 *     So no sample is ever nearer a wall than the shaped line, and never under the hard minimum where the line keeps
 *     it.</li>
 * </ol>
 *
 * <p>Pure apart from the clock; one instance may be shared by threads (each call has its own shaper and adapter).
 */
public final class WalkPathShaper {

    /** Blocks either side of the path the clearance map covers (DESIGN § 7.1.5: 8). */
    public static final double STRIP_HALF_WIDTH = 8.0;
    /** The furthest a curve starts before a corner (and ends after it). */
    public static final double CORNER_REACH = 2.0;
    /** A corner whose curve would start nearer than this to it stays sharp. */
    static final double MIN_CORNER = 0.25;
    /** Spacing of the samples a curve is drawn through. */
    static final double CURVE_SAMPLE = 0.25;
    /** Spacing of the clearance checks along the curve's chords (and of the shaped line round a corner). */
    static final double CHECK = 0.05;
    /** A corner turning less than this (radians) is no corner. */
    static final double MIN_TURN = Math.toRadians(5);
    /** The string pulling's tolerance ({@code CorridorShaper}): a shaped line keeps its points' clearance less this. */
    static final double SLACK = 0.05;

    /**
     * How walk paths are shaped.
     *
     * @param halfWidth     blocks either side of the path the clearance map covers (at least 1)
     * @param wallClearance the clearance kept from walls where there is room ({@code walk.wall-clearance},
     *                      {@link MovementProfile#MIN_WALL_CLEARANCE}..{@link MovementProfile#MAX_WALL_CLEARANCE})
     * @param curve         whether corners are drawn as checked curves ({@code walk.curve})
     */
    public record Options(double halfWidth, double wallClearance, boolean curve) {
        public Options {
            if (!(halfWidth >= 1) || Double.isInfinite(halfWidth)) {
                throw new IllegalArgumentException("halfWidth must be a finite number >= 1: " + halfWidth);
            }
            if (!(wallClearance >= MovementProfile.MIN_WALL_CLEARANCE && wallClearance <= MovementProfile.MAX_WALL_CLEARANCE)) {
                throw new IllegalArgumentException("wallClearance must be " + MovementProfile.MIN_WALL_CLEARANCE + ".."
                    + MovementProfile.MAX_WALL_CLEARANCE + ": " + wallClearance);
            }
        }

        /** The design's strip ({@link #STRIP_HALF_WIDTH}) with the given clearance and curve. */
        public static Options of(double wallClearance, boolean curve) {
            return new Options(STRIP_HALF_WIDTH, wallClearance, curve);
        }
    }

    /**
     * A shaped walk path.
     *
     * @param line    the shaped polyline as floor points ({@code {x, floorY, z}}, not at block centres), start first
     * @param trail   what the trail draws: the curve, or {@code line} itself without one
     * @param runs    runs shaped (pieces of the path between ladders and drops, two cells or more)
     * @param corners corners of the shaped line
     * @param rounded corners drawn as a curve
     * @param nanos   time the shaping took
     */
    public record Shaped(List<double[]> line, List<double[]> trail, int runs, int corners, int rounded, long nanos) {
        public Shaped {
            line = Collections.unmodifiableList(new ArrayList<>(line));
            trail = Collections.unmodifiableList(new ArrayList<>(trail));
        }
    }

    private static final String WORLD = "walk";

    private final LongSupplier nanoTime;

    /** Timed by {@link System#nanoTime()}. */
    public WalkPathShaper() {
        this(System::nanoTime);
    }

    /** @param nanoTime the clock {@link Shaped#nanos()} is read from (tests: a fake) */
    public WalkPathShaper(LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    /**
     * Shapes a walk path over the terrain its search read.
     *
     * @param path    a found (or partial) path of that search
     * @param terrain the request's captured terrain
     * @param profile the request's mover
     * @param access  the request's access (the shaped line never moves onto a cell it blocks)
     */
    public Shaped shape(WalkPath path, WalkTerrain terrain, MovementProfile profile, CellAccess access, Options options) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        long start = nanoTime.getAsLong();
        WalkTerrainNavWorld world = new WalkTerrainNavWorld(terrain, profile, access);
        CorridorShaper shaper = new CorridorShaper(() -> 0L, nanoTime);
        CorridorShaper.Options shapeOptions = new CorridorShaper.Options(options.halfWidth(), options.wallClearance());
        List<double[]> line = new ArrayList<>();
        List<double[]> trail = new ArrayList<>();
        int runs = 0;
        int[] corners = new int[2];
        int i = 0;
        while (i < path.size()) {
            int e = runEnd(path, i);
            List<double[]> raw = new ArrayList<>(e - i);
            for (int k = i; k < e; k++) {
                long key = path.cell(k);
                raw.add(new double[] {BlockKey.x(key) + 0.5, path.floorY(k), BlockKey.z(key) + 0.5});
            }
            if (e - i < 2) {
                append(line, raw);
                append(trail, raw);
                i = e;
                continue;
            }
            List<double[]> piece = new ArrayList<>();
            if (i > 0) {
                piece.add(raw.get(0)); // after a ladder or a drop: the join stays where the search put it
            }
            List<Corridor> corridors = new ArrayList<>();
            double[] carry = null;
            int j = 0;
            while (j < raw.size()) {
                // the next part: from where the last one's shaped line ended, as far as no column comes back on
                // another floor (the clearance map holds one floor per column)
                List<double[]> part = new ArrayList<>();
                java.util.Map<Long, Integer> floors = new java.util.HashMap<>();
                boolean fromCarry = false;
                if (carry != null) {
                    java.util.Map<Long, Integer> probe = new java.util.HashMap<>();
                    fits(probe, carry);
                    // rare: the join lies over the next cell's column on another floor; the part then starts at the cell
                    fromCarry = fits(probe, raw.get(j));
                    if (fromCarry) {
                        fits(floors, carry);
                        part.add(carry);
                    }
                }
                part.add(raw.get(j));
                fits(floors, raw.get(j));
                j++;
                while (j < raw.size() && fits(floors, raw.get(j))) {
                    part.add(raw.get(j));
                    j++;
                }
                boolean last = j >= raw.size();
                List<NavPoint> feet = new ArrayList<>(part.size());
                List<Integer> indexes = new ArrayList<>(part.size());
                for (int k = 0; k < part.size(); k++) {
                    double[] p = part.get(k);
                    feet.add(new NavPoint(WORLD, p[0], p[1] + 1, p[2]));
                    indexes.add(k);
                }
                CorridorShaper.ShapedLine shaped = shaper.shape(world, feet, indexes, shapeOptions, last);
                corridors.addAll(shaped.corridors());
                if (fromCarry) {
                    append(piece, List.of(carry));
                }
                for (NavPoint p : shaped.waypoints()) {
                    append(piece, List.of(new double[] {p.x(), p.blockY() - 1, p.z()}));
                }
                carry = piece.get(piece.size() - 1);
            }
            runs++;
            append(line, piece);
            append(trail, options.curve() ? curve(piece, corridors, options.wallClearance(), corners) : piece);
            i = e;
        }
        if (!options.curve()) {
            corners[0] = countCorners(line);
        }
        return new Shaped(line, trail, runs, corners[0], corners[1], Math.max(0, nanoTime.getAsLong() - start));
    }

    /** Columns round a part's cells looked at for another level: the shaper moves a point up to 2.5 blocks. */
    static final int LEVEL_REACH = 3;

    /**
     * Records a point's column and floor; false when a column within {@link #LEVEL_REACH} of it already holds a floor
     * that differs by more than their distance (a stair turning over itself, a switchback's other flight): the shaper's
     * clearance map holds one floor per column and would take that level for a wall beside the line, so the run is cut
     * there and the next part is shaped from where this one's shaped line ended. A straight stair (one block up per
     * block along) never cuts.
     */
    private static boolean fits(java.util.Map<Long, Integer> floors, double[] p) {
        int x = (int) Math.floor(p[0]);
        int z = (int) Math.floor(p[2]);
        int floor = (int) Math.floor(p[1] + 0.5);
        for (int dx = -LEVEL_REACH; dx <= LEVEL_REACH; dx++) {
            for (int dz = -LEVEL_REACH; dz <= LEVEL_REACH; dz++) {
                Integer known = floors.get(column(x + dx, z + dz));
                if (known != null && Math.abs(known - floor) > Math.max(Math.abs(dx), Math.abs(dz))) {
                    return false;
                }
            }
        }
        floors.putIfAbsent(column(x, z), floor);
        return true;
    }

    private static long column(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    /** The end (exclusive) of the run starting at {@code i}: standing cells joined by steps of at most one block. */
    static int runEnd(WalkPath path, int i) {
        if (path.isLadder(i)) {
            return i + 1;
        }
        int e = i + 1;
        while (e < path.size() && !path.isLadder(e) && Math.abs(path.floorY(e) - path.floorY(e - 1)) <= 1) {
            e++;
        }
        return e;
    }

    private static void append(List<double[]> to, List<double[]> points) {
        for (double[] p : points) {
            if (to.isEmpty() || !same(to.get(to.size() - 1), p)) {
                to.add(p);
            }
        }
    }

    private static boolean same(double[] a, double[] b) {
        return Math.abs(a[0] - b[0]) < 1e-9 && Math.abs(a[1] - b[1]) < 1e-9 && Math.abs(a[2] - b[2]) < 1e-9;
    }

    private static int countCorners(List<double[]> line) {
        int n = 0;
        for (int i = 1; i + 1 < line.size(); i++) {
            if (turn(line.get(i - 1), line.get(i), line.get(i + 1)) >= MIN_TURN) {
                n++;
            }
        }
        return n;
    }

    /** The angle (radians) the line turns at {@code b}, horizontally; 0 for a degenerate corner. */
    static double turn(double[] a, double[] b, double[] c) {
        double ux = b[0] - a[0];
        double uz = b[2] - a[2];
        double vx = c[0] - b[0];
        double vz = c[2] - b[2];
        double lu = Math.hypot(ux, uz);
        double lv = Math.hypot(vx, vz);
        if (lu < 1e-9 || lv < 1e-9) {
            return 0;
        }
        double cos = Math.max(-1, Math.min(1, (ux * vx + uz * vz) / (lu * lv)));
        return Math.acos(cos);
    }

    // ==================== the curve ====================

    /**
     * The shaped line with its corners rounded where the curve's samples pass the check against the clearance map.
     *
     * @param counts {@code [0]} += corners, {@code [1]} += corners rounded
     */
    static List<double[]> curve(List<double[]> line, List<Corridor> corridors, double wallClearance, int[] counts) {
        if (line.size() < 3) {
            return new ArrayList<>(line);
        }
        List<double[]> out = new ArrayList<>();
        out.add(line.get(0));
        double[] prevEnd = line.get(0); // where the last corner's curve ended: the next one starts after it
        for (int i = 1; i + 1 < line.size(); i++) {
            double[] a = line.get(i - 1);
            double[] b = line.get(i);
            double[] c = line.get(i + 1);
            if (turn(a, b, c) < MIN_TURN) {
                out.add(b);
                prevEnd = b;
                continue;
            }
            counts[0]++;
            double before = Math.min(horizontal(prevEnd, b), horizontal(a, b) / 2);
            double after = horizontal(b, c) / 2;
            double reach = Math.min(CORNER_REACH, Math.min(before, after));
            List<double[]> rounded = null;
            double need = need(a, b, c, reach, corridors, wallClearance);
            while (!Double.isNaN(need) && reach >= MIN_CORNER && rounded == null) {
                rounded = round(a, b, c, reach, corridors, wallClearance, need);
                reach /= 2;
            }
            if (rounded == null) {
                out.add(b);
                prevEnd = b;
            } else {
                counts[1]++;
                out.addAll(rounded);
                prevEnd = rounded.get(rounded.size() - 1);
            }
        }
        out.add(line.get(line.size() - 1));
        return out;
    }

    /**
     * The clearance a curve round corner {@code b} must keep: the least the shaped line has from {@code reach} before
     * the corner to {@code reach} after it, at most {@code wall-clearance}. NaN when the line
     * itself is not on the clearance map there (no curve).
     */
    private static double need(double[] a, double[] b, double[] c, double reach, List<Corridor> corridors,
                               double wallClearance) {
        double least = wallClearance;
        for (double[][] side : new double[][][] {{b, a}, {b, c}}) {
            double len = horizontal(side[0], side[1]);
            for (double t = 0; t <= reach + 1e-9; t += CHECK) {
                double[] p = lerp(side[0], side[1], Math.min(1, t / len));
                double clear = clearanceAt(p, corridors, wallClearance);
                if (Double.isNaN(clear)) {
                    return Double.NaN;
                }
                least = Math.min(least, clear);
            }
        }
        return least - 1e-6;
    }

    /** The curve round {@code b} from {@code reach} before it to {@code reach} after it, or null when a sample fails. */
    private static List<double[]> round(double[] a, double[] b, double[] c, double reach, List<Corridor> corridors,
                                        double wallClearance, double need) {
        double[] p = lerp(b, a, reach / horizontal(a, b));
        double[] q = lerp(b, c, reach / horizontal(b, c));
        int samples = Math.max(2, (int) Math.ceil(2 * reach / CURVE_SAMPLE));
        List<double[]> out = new ArrayList<>(samples + 1);
        for (int k = 0; k <= samples; k++) {
            double t = (double) k / samples;
            double u = 1 - t;
            double[] s = new double[3];
            for (int axis = 0; axis < 3; axis++) {
                s[axis] = u * u * p[axis] + 2 * u * t * b[axis] + t * t * q[axis];
            }
            // the drawn chord from the last sample to this one, every CHECK blocks, ends included
            double[] from = out.isEmpty() ? s : out.get(out.size() - 1);
            int checks = Math.max(1, (int) Math.ceil(horizontal(from, s) / CHECK));
            for (int h = out.isEmpty() ? checks : 1; h <= checks; h++) {
                double clear = clearanceAt(lerp(from, s, (double) h / checks), corridors, wallClearance);
                if (Double.isNaN(clear) || clear < need) {
                    return null;
                }
            }
            out.add(s);
        }
        return out;
    }

    /**
     * The clearance (at most {@code cap}) of a floor point on the clearance map: in the corridor whose floor at its
     * column is within a step of the point's height; NaN when no corridor has its column at that height.
     */
    static double clearanceAt(double[] p, List<Corridor> corridors, double cap) {
        int x = (int) Math.floor(p[0]);
        int z = (int) Math.floor(p[2]);
        int floorY = (int) Math.floor(p[1] + 0.5);
        for (Corridor corridor : corridors) {
            int f = corridor.floorAt(x, z);
            if (f != Corridor.NONE && Math.abs(f - floorY) <= 1) {
                return corridor.clearanceAt(p[0], p[2], cap);
            }
        }
        return Double.NaN;
    }

    private static double horizontal(double[] a, double[] b) {
        return Math.hypot(b[0] - a[0], b[2] - a[2]);
    }

    private static double[] lerp(double[] from, double[] to, double t) {
        return new double[] {from[0] + (to[0] - from[0]) * t, from[1] + (to[1] - from[1]) * t,
            from[2] + (to[2] - from[2]) * t};
    }
}

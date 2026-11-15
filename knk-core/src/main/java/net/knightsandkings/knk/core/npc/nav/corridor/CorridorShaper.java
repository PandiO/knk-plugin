package net.knightsandkings.knk.core.npc.nav.corridor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import net.knightsandkings.knk.core.npc.nav.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NavWorld;

/**
 * Corridor steering, option A (implementation plan 4c.3 decision 1, link M2; BEHAVIOR_MODEL NAV-24): re-places a
 * stretch of an NPC's waypoint line inside its {@link Corridor} before the engine walks it.
 *
 * <ol>
 *     <li><b>Clearance.</b> The line is looked at every {@link #DENSE} blocks. Each point may move square to the line,
 *     within the corridor and on its own floor, to where it keeps {@code wall-clearance} from every wall, post or drop,
 *     the nearest such place to the road line; the places allowed over ±{@link #WINDOW} points are intersected first,
 *     so an indentation does not pull the line into it and a jutting piece is seen before it is reached. Where the road
 *     is narrower than twice the clearance, the point goes to the place with the most clearance (the middle).</li>
 *     <li><b>Chokepoints.</b> Where the free width across the line drops below {@link #CHOKE_WIDTH} blocks for at most
 *     {@link #CHOKE_LENGTH} blocks with wider road before and after (a door, an arch, an entrance), the line goes
 *     through the opening's middle on its axis: a waypoint {@link #CHOKE_LEAD} blocks before it, one in it and one
 *     after it.</li>
 *     <li><b>String pulling.</b> The moved points are joined by the longest straight steps that stay in the corridor,
 *     keep floor continuity and keep at least the clearance the moved points had (at most {@code wall-clearance}):
 *     the step check with the body's width plus the clearance, wobble removed, corners kept where the corridor
 *     bends.</li>
 * </ol>
 *
 * <p>A stretch whose line passes the same block column on two levels (a staircase turning over itself) is split into
 * pieces with one corridor each. The result is cached per stretch for {@link #TTL_TICKS} ticks and shared by every NPC
 * that gets the same stretch (plan 4c.3 decision 1.5); the existing steps (corner clearance, the waypoint chain) still
 * run on the shaped line, per NPC, as the safety net (decision 4). Main thread.
 */
public final class CorridorShaper {

    /** Spacing of the points the line is shaped at. */
    public static final double DENSE = 0.5;
    /** Points either side whose allowed places a point's place must also fit (±2 blocks). */
    static final int WINDOW = 4;
    /** A free width under this many blocks is a chokepoint (plan 4c.3 decision 1.4). */
    public static final double CHOKE_WIDTH = 2.0;
    /** ... when it lasts at most this many blocks along the line. */
    public static final double CHOKE_LENGTH = 3.0;
    /** Blocks before and after a chokepoint's middle of its aligned waypoints. */
    public static final double CHOKE_LEAD = 1.5;
    /** Waypoint margin on a stretch with a chokepoint (decision 1.4: 0.35 on the aligned waypoints). */
    public static final double CHOKE_MARGIN = 0.35;
    /** How long a shaped stretch is reused (blocks can change: doors, builds). */
    public static final long TTL_TICKS = 600;
    /** Shaped stretches kept at most. */
    static final int MAX_CACHED = 512;
    /** Blocks either side of the line the clearance across it is looked at. */
    static final double LATERAL_SEARCH = 2.5;
    /** Tolerance of the string pulling's clearance check. */
    private static final double SLACK = 0.05;
    /** Spacing of the string pulling's samples along a step. */
    private static final double PULL_SAMPLE = 0.25;
    /** The body's half width, for the step check's corners. */
    private static final double HALF_BODY = 0.3;

    /** {@code wall-clearance}'s hard minimum (plan 4c.6 question 4): the body's half width plus 0.05. */
    public static final double HARD_MINIMUM = 0.35;

    /** The decided values (plan 4c.6 questions 3 and 4) a stretch is shaped with. */
    public record Options(double halfWidth, double wallClearance) {
        public Options {
            if (!(halfWidth >= 1) || !(wallClearance >= HARD_MINIMUM)) {
                throw new IllegalArgumentException("corridor-half-width >= 1 and wall-clearance >= " + HARD_MINIMUM);
            }
        }
    }

    /**
     * A shaped stretch.
     *
     * @param waypoints   feet positions (not at block centres)
     * @param lineIndexes for each waypoint, the index of the route line point it came from (nearest)
     * @param freeWidths  for each waypoint, the free width across the line there (M3's fit)
     * @param chokepoints chokepoints aligned on this stretch
     * @param corridors   the corridors it was shaped in (one per piece)
     */
    public record ShapedLine(List<NavPoint> waypoints, List<Integer> lineIndexes, List<FreeWidth> freeWidths,
                             int chokepoints, List<Corridor> corridors) {
        public ShapedLine {
            waypoints = List.copyOf(waypoints);
            lineIndexes = List.copyOf(lineIndexes);
            freeWidths = List.copyOf(freeWidths);
            corridors = List.copyOf(corridors);
        }

        /** The corridor that holds a feet position, or null (for M3 and M6: the clearance where an NPC stands). */
        public Corridor corridorAt(NavPoint feet) {
            for (Corridor c : corridors) {
                if (c.world().equals(feet.world()) && c.floorAt(feet.blockX(), feet.blockZ()) == feet.blockY() - 1) {
                    return c;
                }
            }
            return null;
        }
    }

    /** What a stretch is cached by: its world, its points (feet) with their line indexes, the options. */
    private record Key(List<NavPoint> feet, List<Integer> indexes, Options options, boolean keepLast) {
    }

    private record Cached(ShapedLine line, long until) {
    }

    private final LongSupplier clock;
    private final LongSupplier nanoTime;
    private final Map<Key, Cached> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, Cached> eldest) {
            return size() > MAX_CACHED;
        }
    };
    private long shaped;
    private long shared;
    private long nanos;
    private long reads;

    /**
     * @param clock    the current tick (for the cache's {@link #TTL_TICKS})
     * @param nanoTime a nanosecond clock for the cost figure (null: {@link System#nanoTime})
     */
    public CorridorShaper(LongSupplier clock, LongSupplier nanoTime) {
        this.clock = clock;
        this.nanoTime = nanoTime == null ? System::nanoTime : nanoTime;
    }

    /** Stretches shaped (each built its corridors once). */
    public long shaped() {
        return shaped;
    }

    /** Stretches handed out again from the cache (another NPC on the same stretch: no world read). */
    public long shared() {
        return shared;
    }

    /** Nanoseconds spent shaping. */
    public long nanos() {
        return nanos;
    }

    /** World reads of every corridor built. */
    public long reads() {
        return reads;
    }

    /** Forgets every shaped stretch. */
    public void clear() {
        cache.clear();
    }

    /**
     * The stretch's waypoints shaped in their corridor, from the cache when another NPC had the same stretch.
     *
     * @param feet     the stretch's waypoints (feet on standable floors, in order)
     * @param indexes  for each, the route line index it came from
     * @param keepLast true when the last waypoint is the route's end: it stays where it is
     */
    public ShapedLine shape(NavWorld world, List<NavPoint> feet, List<Integer> indexes, Options options,
                            boolean keepLast) {
        if (feet.size() != indexes.size()) {
            throw new IllegalArgumentException("one line index per waypoint");
        }
        Key key = new Key(List.copyOf(feet), List.copyOf(indexes), options, keepLast);
        long now = clock.getAsLong();
        Cached hit = cache.get(key);
        if (hit != null && now < hit.until()) {
            shared++;
            return hit.line();
        }
        long start = nanoTime.getAsLong();
        ShapedLine line = shapeNow(world, key.feet(), key.indexes(), options, keepLast);
        nanos += nanoTime.getAsLong() - start;
        shaped++;
        for (Corridor c : line.corridors()) {
            reads += c.reads();
        }
        cache.put(key, new Cached(line, now + TTL_TICKS));
        return line;
    }

    // ==================== the shaping ====================

    static ShapedLine shapeNow(NavWorld world, List<NavPoint> feet, List<Integer> indexes, Options options,
                               boolean keepLast) {
        List<NavPoint> outPoints = new ArrayList<>();
        List<Integer> outIndexes = new ArrayList<>();
        List<FreeWidth> outWidths = new ArrayList<>();
        List<Corridor> corridors = new ArrayList<>();
        int chokepoints = 0;
        int s = 0;
        while (s < feet.size()) {
            int e = feet.size();
            Corridor corridor = Corridor.build(world, feet.subList(s, e), options.halfWidth());
            int conflict = firstConflict(corridor, feet, s, e);
            if (conflict > s) {
                e = conflict; // the line comes back over itself on another level: a piece of its own
                corridor = Corridor.build(world, feet.subList(s, e), options.halfWidth());
            } else if (conflict == s) {
                e = s + 1; // cannot happen (the first point is its corridor's root); stay safe
            }
            corridors.add(corridor);
            boolean pinLast = keepLast && e == feet.size();
            Piece piece = new Piece(corridor, feet.subList(s, e), indexes.subList(s, e), options, pinLast);
            chokepoints += piece.shape();
            outPoints.addAll(piece.outPoints);
            outIndexes.addAll(piece.outIndexes);
            outWidths.addAll(piece.outWidths);
            s = e;
        }
        return new ShapedLine(outPoints, outIndexes, outWidths, chokepoints, corridors);
    }

    /** The first point in [s, e) whose own cell has another floor in the corridor (another level); -1 if none. */
    private static int firstConflict(Corridor corridor, List<NavPoint> feet, int s, int e) {
        for (int i = s; i < e; i++) {
            NavPoint p = feet.get(i);
            if (corridor.floorAt(p.blockX(), p.blockZ()) != p.blockY() - 1) {
                return i;
            }
        }
        return -1;
    }

    /** A point of the line being shaped: where it is, its floor, the line index, whether it must stay. */
    private record Dense(double x, double z, int floor, int index, boolean pinned) {
    }

    /** One piece of a stretch with one corridor. */
    private static final class Piece {
        private final Corridor corridor;
        private final List<NavPoint> feet;
        private final List<Integer> indexes;
        private final Options options;
        private final boolean pinLast;
        private final String world;
        final List<NavPoint> outPoints = new ArrayList<>();
        final List<Integer> outIndexes = new ArrayList<>();
        final List<FreeWidth> outWidths = new ArrayList<>();

        Piece(Corridor corridor, List<NavPoint> feet, List<Integer> indexes, Options options, boolean pinLast) {
            this.corridor = corridor;
            this.feet = feet;
            this.indexes = indexes;
            this.options = options;
            this.pinLast = pinLast;
            this.world = feet.get(0).world();
        }

        /** Shapes the piece into the out lists; returns the chokepoints aligned. */
        int shape() {
            List<Dense> dense = densify();
            if (dense.size() < 2) {
                for (int k = 0; k < feet.size(); k++) {
                    emit(new Dense(feet.get(k).x(), feet.get(k).z(), feet.get(k).blockY() - 1, indexes.get(k),
                        true), 1, 0);
                }
                return 0;
            }
            int n = dense.size();
            double[][] normal = new double[n][];
            double[] left = new double[n];
            double[] right = new double[n];
            double[] lo = new double[n];
            double[] hi = new double[n];
            boolean[] good = new boolean[n];
            double[] best = new double[n];
            double w = options.wallClearance();
            for (int j = 0; j < n; j++) {
                Dense a = dense.get(Math.max(0, j - 1));
                Dense b = dense.get(Math.min(n - 1, j + 1));
                double hx = b.x() - a.x();
                double hz = b.z() - a.z();
                double len = Math.hypot(hx, hz);
                if (len < 1e-9) {
                    hx = 1;
                    hz = 0;
                    len = 1;
                }
                hx /= len;
                hz /= len;
                normal[j] = new double[] {-hz, hx}; // to the right of the heading
                Dense d = dense.get(j);
                right[j] = corridor.reach(d.x(), d.z(), normal[j][0], normal[j][1], options.halfWidth());
                left[j] = corridor.reach(d.x(), d.z(), -normal[j][0], -normal[j][1], options.halfWidth());
                profile(d, normal[j], left[j], right[j], w, j, lo, hi, good, best);
            }
            // where each point goes: the allowed places of its window intersected, nearest the road line
            double[] offset = new double[n];
            for (int j = 0; j < n; j++) {
                if (dense.get(j).pinned()) {
                    offset[j] = 0;
                    continue;
                }
                if (!good[j]) {
                    offset[j] = best[j];
                    continue;
                }
                double l = lo[j];
                double h = hi[j];
                for (int k = Math.max(0, j - WINDOW); k <= Math.min(n - 1, j + WINDOW); k++) {
                    if (k == j || !good[k] || !parallel(normal[j], normal[k])) {
                        continue;
                    }
                    double nl = Math.max(l, lo[k]);
                    double nh = Math.min(h, hi[k]);
                    if (nl <= nh) {
                        l = nl;
                        h = nh;
                    }
                }
                offset[j] = Math.max(l, Math.min(h, 0));
            }
            List<Dense> moved = new ArrayList<>(n);
            for (int j = 0; j < n; j++) {
                Dense d = dense.get(j);
                double x = d.x() + offset[j] * normal[j][0];
                double z = d.z() + offset[j] * normal[j][1];
                int f = corridor.floorAt((int) Math.floor(x), (int) Math.floor(z));
                if (f == Corridor.NONE || Math.abs(f - d.floor()) > 1) {
                    x = d.x();
                    z = d.z();
                    f = d.floor();
                }
                moved.add(new Dense(x, z, f, d.index(), d.pinned()));
            }
            int chokes = alignChokepoints(dense, moved, normal, left, right);
            pull(moved);
            return chokes;
        }

        private static boolean parallel(double[] a, double[] b) {
            return a[0] * b[0] + a[1] * b[1] > 0.9;
        }

        /**
         * The clearance across the line at one point: the places with at least the clearance (the run nearest the
         * line), or, where there are none, the place with the most.
         */
        private void profile(Dense d, double[] n, double left, double right, double w, int j, double[] lo,
                             double[] hi, boolean[] good, double[] best) {
            // places further than this from the line are never needed: a wider road has places with the
            // clearance nearer, and the middle of a narrower one is within it
            int maxL = (int) Math.floor(Math.min(left, LATERAL_SEARCH) / Corridor.LATERAL_STEP + 1e-9);
            int maxR = (int) Math.floor(Math.min(right, LATERAL_SEARCH) / Corridor.LATERAL_STEP + 1e-9);
            double bestC = clear(d, n, 0, w);
            double bestS = 0;
            int at = Integer.MIN_VALUE; // the good place nearest the line, in steps
            if (bestC >= w - 1e-9) {
                at = 0;
            } else {
                // outwards from the line, both sides in turn, to the first place with the clearance
                for (int k = 1; k <= Math.max(maxL, maxR) && at == Integer.MIN_VALUE; k++) {
                    for (int side : new int[] {1, -1}) {
                        if ((side > 0 ? maxR : maxL) < k) {
                            continue;
                        }
                        double sOff = side * k * Corridor.LATERAL_STEP;
                        double c = clear(d, n, sOff, w);
                        if (c > bestC + 1e-9) {
                            bestC = c;
                            bestS = sOff;
                        }
                        if (c >= w - 1e-9) {
                            at = side * k;
                            break;
                        }
                    }
                }
            }
            best[j] = bestS;
            if (at == Integer.MIN_VALUE) {
                good[j] = false;
                return;
            }
            good[j] = true;
            int a = at;
            while (a - 1 >= -maxL && clear(d, n, (a - 1) * Corridor.LATERAL_STEP, w) >= w - 1e-9) {
                a--;
            }
            int b = at;
            while (b + 1 <= maxR && clear(d, n, (b + 1) * Corridor.LATERAL_STEP, w) >= w - 1e-9) {
                b++;
            }
            lo[j] = a * Corridor.LATERAL_STEP;
            hi[j] = b * Corridor.LATERAL_STEP;
        }

        /** The clearance, up to {@code cap}, of the place {@code sOff} blocks to the right of a point. */
        private double clear(Dense d, double[] n, double sOff, double cap) {
            return corridor.clearanceAt(d.x() + sOff * n[0], d.z() + sOff * n[1], cap);
        }

        /** The line every {@link #DENSE} blocks between the waypoints, on the corridor's floors. */
        private List<Dense> densify() {
            List<Dense> out = new ArrayList<>();
            int last = feet.size() - 1;
            for (int k = 0; k < feet.size(); k++) {
                NavPoint p = feet.get(k);
                out.add(new Dense(p.x(), p.z(), p.blockY() - 1, indexes.get(k), pinLast && k == last));
                if (k == last) {
                    break;
                }
                NavPoint q = feet.get(k + 1);
                double len = Math.hypot(q.x() - p.x(), q.z() - p.z());
                int parts = (int) Math.floor(len / DENSE);
                for (int i = 1; i < parts; i++) {
                    double t = i * DENSE / len;
                    double x = p.x() + (q.x() - p.x()) * t;
                    double z = p.z() + (q.z() - p.z()) * t;
                    int f = corridor.floorAt((int) Math.floor(x), (int) Math.floor(z));
                    int lowF = Math.min(p.blockY(), q.blockY()) - 1;
                    int highF = Math.max(p.blockY(), q.blockY()) - 1;
                    if (f == Corridor.NONE || f < lowF - 1 || f > highF + 1) {
                        continue; // the straight walk leaves the corridor here; the waypoints stay
                    }
                    out.add(new Dense(x, z, f, t < 0.5 ? indexes.get(k) : indexes.get(k + 1), false));
                }
            }
            return out;
        }

        /**
         * Puts the line through the middle of each chokepoint on its axis (plan 4c.3 decision 1.4): the points
         * round it are replaced by three pinned ones. Returns how many.
         */
        private int alignChokepoints(List<Dense> dense, List<Dense> moved, double[][] normal, double[] left,
                                     double[] right) {
            int n = dense.size();
            int chokes = 0;
            // the narrowest width through each point, whatever way the line crosses (a door taken diagonally)
            double[] width = new double[n];
            for (int k = 0; k < n; k++) {
                width[k] = left[k] + right[k] < CHOKE_WIDTH + 1 || k == 0 || k == n - 1
                    ? corridor.narrowest(dense.get(k).x(), dense.get(k).z(), options.halfWidth())[0]
                    : left[k] + right[k];
            }
            int j = 0;
            List<int[]> runs = new ArrayList<>();
            while (j < n) {
                if (width[j] >= CHOKE_WIDTH - 1e-6) {
                    j++;
                    continue;
                }
                int a = j;
                while (j < n && width[j] < CHOKE_WIDTH - 1e-6) {
                    j++;
                }
                int b = j - 1;
                double length = Math.hypot(dense.get(b).x() - dense.get(a).x(), dense.get(b).z() - dense.get(a).z());
                if (a > 0 && j < n && length <= CHOKE_LENGTH) {
                    runs.add(new int[] {a, b});
                }
            }
            for (int r = runs.size() - 1; r >= 0; r--) {
                int a = runs.get(r)[0];
                int b = runs.get(r)[1];
                int mid = (a + b) / 2;
                Dense d = dense.get(mid);
                double[] narrow = corridor.narrowest(d.x(), d.z(), options.halfWidth());
                // the opening's middle across its narrowest direction
                double toPlus = corridor.reach(d.x(), d.z(), narrow[1], narrow[2], options.halfWidth());
                double toMinus = corridor.reach(d.x(), d.z(), -narrow[1], -narrow[2], options.halfWidth());
                double sOff = (toPlus - toMinus) / 2;
                double cx = d.x() + sOff * narrow[1];
                double cz = d.z() + sOff * narrow[2];
                int cf = corridor.floorAt((int) Math.floor(cx), (int) Math.floor(cz));
                if (cf == Corridor.NONE) {
                    continue;
                }
                double hx = dense.get(Math.min(n - 1, b + 1)).x() - dense.get(Math.max(0, a - 1)).x();
                double hz = dense.get(Math.min(n - 1, b + 1)).z() - dense.get(Math.max(0, a - 1)).z();
                double[] axis = {-narrow[2], narrow[1]}; // square to the narrowest direction, along the walk
                if (axis[0] * hx + axis[1] * hz < 0) {
                    axis[0] = -axis[0];
                    axis[1] = -axis[1];
                }
                // and the middle of the narrow part along the axis (a door's middle, not where the line crossed it)
                double ahead = narrowFor(cx, cz, axis[0], axis[1]);
                double behind = narrowFor(cx, cz, -axis[0], -axis[1]);
                cx += (ahead - behind) / 2 * axis[0];
                cz += (ahead - behind) / 2 * axis[1];
                cf = corridor.floorAt((int) Math.floor(cx), (int) Math.floor(cz));
                if (cf == Corridor.NONE) {
                    continue;
                }
                List<Dense> pins = new ArrayList<>(3);
                Dense before = lead(cx, cz, -axis[0], -axis[1], dense.get(mid).index());
                if (before != null) {
                    pins.add(before);
                }
                pins.add(new Dense(cx, cz, cf, d.index(), true));
                Dense after = lead(cx, cz, axis[0], axis[1], dense.get(mid).index());
                if (after != null) {
                    pins.add(after);
                }
                // replace the moved points within the lead round the middle (along the axis) by the pins
                int from = -1;
                int to = -1;
                for (int k = Math.max(0, a - 6); k <= Math.min(moved.size() - 1, b + 6); k++) {
                    Dense m = moved.get(k);
                    double along = (m.x() - cx) * axis[0] + (m.z() - cz) * axis[1];
                    double across = Math.abs((m.x() - cx) * -axis[1] + (m.z() - cz) * axis[0]);
                    if (Math.abs(along) <= CHOKE_LEAD + 0.25 && across <= CHOKE_LEAD + 1 && !m.pinned()) {
                        if (from < 0) {
                            from = k;
                        }
                        to = k;
                    }
                }
                if (from < 0) {
                    continue;
                }
                for (int k = to; k >= from; k--) {
                    moved.remove(k);
                }
                moved.addAll(from, pins);
                chokes++;
            }
            return chokes;
        }

        /** How far along {@code (dx, dz)} from a chokepoint point the place stays narrow, in steps of 0.1. */
        private double narrowFor(double x, double z, double dx, double dz) {
            double t = 0;
            while (t + 0.1 <= CHOKE_LENGTH) {
                double nx = x + dx * (t + 0.1);
                double nz = z + dz * (t + 0.1);
                if (corridor.reach(x, z, dx, dz, t + 0.1) < t + 0.1 - 1e-9
                    || corridor.narrowest(nx, nz, options.halfWidth())[0] >= CHOKE_WIDTH - 1e-6) {
                    break;
                }
                t += 0.1;
            }
            return t;
        }

        /** The aligned waypoint {@link #CHOKE_LEAD} blocks from the middle along {@code (dx, dz)}, or nearer; null if none. */
        private Dense lead(double cx, double cz, double dx, double dz, int index) {
            double reach = corridor.reach(cx, cz, dx, dz, CHOKE_LEAD + HALF_BODY);
            double t = Math.min(CHOKE_LEAD, reach - HALF_BODY);
            if (t < 0.5) {
                return null;
            }
            double x = cx + dx * t;
            double z = cz + dz * t;
            int f = corridor.floorAt((int) Math.floor(x), (int) Math.floor(z));
            return f == Corridor.NONE ? null : new Dense(x, z, f, index, true);
        }

        /**
         * String pulling over the moved points, into the out lists: from each anchor, the furthest point (not past
         * a pinned one) whose straight step from the anchor fits ({@link #stepFits}, with the least clearance of the
         * points it passes, at most {@code wall-clearance}), found by galloping then halving; every step taken is
         * checked.
         */
        private void pull(List<Dense> moved) {
            int n = moved.size();
            double w = options.wallClearance();
            double[] clear = new double[n];
            double[] prefixMin = new double[n];
            for (int k = 0; k < n; k++) {
                clear[k] = corridor.clearanceAt(moved.get(k).x(), moved.get(k).z(), w);
            }
            emit(moved.get(0), clear[0], 0);
            int a = 0;
            while (a < n - 1) {
                int limit = n - 1;
                for (int k = a + 1; k < n - 1; k++) {
                    if (moved.get(k).pinned()) {
                        limit = k;
                        break;
                    }
                }
                // the least clearance from the anchor up to each point
                prefixMin[a] = Math.min(w, clear[a]);
                for (int k = a + 1; k <= limit; k++) {
                    prefixMin[k] = Math.min(prefixMin[k - 1], clear[k]);
                }
                int good = a + 1; // the next point is always taken
                int step = 1;
                int bad = -1;
                while (good < limit) {
                    int probe = Math.min(limit, good + step);
                    if (fits(moved, a, probe, prefixMin[probe])) {
                        good = probe;
                        step *= 2;
                    } else {
                        bad = probe;
                        break;
                    }
                }
                while (bad > good + 1) {
                    int mid = (good + bad) >>> 1;
                    if (fits(moved, a, mid, prefixMin[mid])) {
                        good = mid;
                    } else {
                        bad = mid;
                    }
                }
                emit(moved.get(good), clear[good], good);
                a = good;
            }
        }

        private boolean fits(List<Dense> moved, int a, int b, double minClear) {
            return stepFits(moved.get(a), moved.get(b), Math.max(minClear, 0) - SLACK);
        }

        /**
         * Whether the straight step a→b is walkable with at least {@code minClear}: in the corridor at every sample,
         * the body's corners too, each floor within a step of both ends' floors and of the sample before.
         */
        private boolean stepFits(Dense a, Dense b, double minClear) {
            double dx = b.x() - a.x();
            double dz = b.z() - a.z();
            double len = Math.hypot(dx, dz);
            int samples = Math.max(1, (int) Math.ceil(len / PULL_SAMPLE));
            int prevFloor = a.floor();
            for (int i = 1; i < samples; i++) {
                double t = (double) i / samples;
                double x = a.x() + dx * t;
                double z = a.z() + dz * t;
                int f = corridor.floorAt((int) Math.floor(x), (int) Math.floor(z));
                if (f == Corridor.NONE || Math.abs(f - prevFloor) > 1 || Math.abs(f - a.floor()) > 1
                    || Math.abs(f - b.floor()) > 1) {
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
                if (minClear > 0 && corridor.clearanceAt(x, z, minClear + SLACK) < minClear) {
                    return false;
                }
                prevFloor = f;
            }
            return true;
        }

        private void emit(Dense d, double clear, int k) {
            NavPoint p = new NavPoint(world, d.x(), d.floor() + 1, d.z());
            if (!outPoints.isEmpty()) {
                NavPoint last = outPoints.get(outPoints.size() - 1);
                if (Math.hypot(last.x() - p.x(), last.z() - p.z()) < 1e-6 && last.blockY() == p.blockY()) {
                    return;
                }
            }
            outPoints.add(p);
            outIndexes.add(d.index());
            NavPoint prev = outPoints.size() > 1 ? outPoints.get(outPoints.size() - 2) : null;
            double hx = prev == null ? 0 : p.x() - prev.x();
            double hz = prev == null ? 0 : p.z() - prev.z();
            outWidths.add(corridor.freeWidth(p, hx, hz));
        }
    }
}

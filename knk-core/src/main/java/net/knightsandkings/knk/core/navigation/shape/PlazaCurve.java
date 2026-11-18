package net.knightsandkings.knk.core.navigation.shape;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The plaza line of the players' trail (off-road navigation phase 7, DESIGN § 8, decisions O09, O19, O29, O30;
 * PandiO/knk-workspace#712): where a route enters a plaza on one road and leaves it on another, a rounded line from the
 * entry to the exit, pulled towards the plaza node, instead of the road graph's sharp bend at the node (the builder
 * collapses a plaza into one junction).
 *
 * <ol>
 *     <li><b>Which junctions.</b> A plaza by the NPC rule (O19, {@link CorridorShaper#resolve}): a designed plaza with
 *     its {@code PlazaRadius} (at most {@code plaza-corridor-blocks}), or a junction whose open floor, measured with
 *     {@link PlazaLine#openRadius}'s 16 rays, is wider than {@code corridor-half-width}; its footprint is the open
 *     radius less the clearance. A street junction gets no line ({@link Kind#STREET}).</li>
 *     <li><b>The curve</b> (§ 8.3). From the entry P0 (where the route crosses into the footprint, tangent t0 along road
 *     A) to the exit P1 (where it leaves, tangent t1 along road B) a cubic Bezier with the control points
 *     P0 + a·t0 and P1 − a·t1, a = |P0P1| / 3; then both control points move together so that the curve's midpoint
 *     lies at the chord's midpoint plus {@code pull} times the way from there to the node N: 0 gives the chord with
 *     rounded ends, 1 a curve through N, the default 0.35 (O30) a gentle bend towards the node.</li>
 *     <li><b>The check</b> (§ 8.4). The curve is sampled every {@value #SAMPLE} block on the clearance map of the plaza
 *     ({@link Corridor#build} round the route with the footprint as a disc, as the NPC plaza line does): every sample
 *     must be in the corridor (standable, with headroom, reached with floor continuity and in a WorldGuard region the
 *     route passes), inside the footprint, a step at most from the last sample's floor, with the body's corners on
 *     floor, at least {@link CorridorShaper#HARD_MINIMUM} from an obstacle, and at least the wanted clearance where the
 *     entry and exit allow it (less only near an end that has less itself, by the distance from that end).</li>
 *     <li><b>The fallback</b> (§ 8.4). A curve that fails is replaced by {@link PlazaLine}'s bounded A* on the
 *     footprint, guided by the curve ({@code plaza-guide-cost} per block of distance from it) and string pulled
 *     ({@link Kind#GUIDED}). When that finds nothing, the route keeps its own polyline ({@link Kind#NONE}).</li>
 * </ol>
 *
 * <p>The route's length, ETA and maneuvers are not this class's business: it only gives the line and where on the
 * route polyline it starts and ends (A25). Pure, over the {@link NavWorld} port; reads the world while it runs (main
 * thread for a live world).
 */
public final class PlazaCurve {

    /** Spacing of the curve's samples (DESIGN § 8.4). */
    public static final double SAMPLE = 0.5;
    /** Default {@code plaza-pull} (O30). */
    public static final double DEFAULT_PULL = 0.35;
    /** Default {@code plaza-guide-cost} (DESIGN § 8.7). */
    public static final double DEFAULT_GUIDE_COST = 2;
    /** Default {@code plaza-corridor-blocks}, shared with the NPC key (DESIGN § 8.7). */
    public static final int DEFAULT_PLAZA_BLOCKS = 16;
    /** Blocks of road beyond the footprint the corridor is built round, so the entries' clearance is known. */
    static final double ROAD_MARGIN = 4;
    private static final double SLACK = 0.05;
    private static final double HALF_BODY = 0.3;

    private PlazaCurve() {
    }

    /**
     * @param halfWidth   {@code corridor-half-width}: the corridor's strip round the route, and the open radius a
     *                    junction must exceed to be a plaza (O19)
     * @param clearance   the clearance the line keeps from obstacles where the plaza allows ({@code wall-clearance})
     * @param plazaBlocks {@code plaza-corridor-blocks}: the largest footprint radius
     * @param pull        {@code plaza-pull}, 0 to 1
     * @param guideCost   {@code plaza-guide-cost}, at least 0
     */
    public record Options(double halfWidth, double clearance, int plazaBlocks, double pull, double guideCost) {
        public Options {
            if (!(halfWidth >= 1) || !(clearance >= CorridorShaper.HARD_MINIMUM)) {
                throw new IllegalArgumentException("corridor-half-width >= 1 and a clearance >= "
                    + CorridorShaper.HARD_MINIMUM);
            }
            if (plazaBlocks < 1) {
                throw new IllegalArgumentException("plaza-corridor-blocks must be at least 1 (got " + plazaBlocks + ")");
            }
            if (!(pull >= 0 && pull <= 1)) {
                throw new IllegalArgumentException("plaza-pull must be 0..1 (got " + pull + ")");
            }
            if (!(guideCost >= 0)) {
                throw new IllegalArgumentException("plaza-guide-cost must be >= 0 (got " + guideCost + ")");
            }
            clearance = Math.min(clearance, Corridor.CAP);
        }

        /** The design defaults with the given corridor half width and clearance. */
        public static Options defaults(double halfWidth, double clearance) {
            return new Options(halfWidth, clearance, DEFAULT_PLAZA_BLOCKS, DEFAULT_PULL, DEFAULT_GUIDE_COST);
        }

        /** These options with another pull. */
        public Options withPull(double pull) {
            return new Options(halfWidth, clearance, plazaBlocks, pull, guideCost);
        }
    }

    /** What the plaza line of one crossing is. */
    public enum Kind {
        /** The pulled curve passed its check. */
        CURVE,
        /** The curve failed its check; the A* guided by it found a line. */
        GUIDED,
        /** Neither found a line: the route keeps its own polyline there (today's behaviour). */
        NONE,
        /** A junction of streets (open floor no wider than {@code corridor-half-width}): no plaza line (O19). */
        STREET,
        /** The route does not pass through the plaza (it does not come near the node, or starts or ends inside). */
        NOT_CROSSED
    }

    /**
     * One plaza crossing worked out.
     *
     * @param kind          what came out
     * @param line          the plaza line from the entry to the exit (floor points), empty unless CURVE or GUIDED
     * @param curve         the pulled curve as sampled, empty when it was never built (STREET, NOT_CROSSED)
     * @param centre        the node: its block's centre at its floor
     * @param radius        the footprint's radius, 0 for STREET and NOT_CROSSED
     * @param entryAlong    where the line starts, in blocks along the route polyline (horizontal), or -1
     * @param exitAlong     where it ends, or -1
     * @param lineLength    the plaza line's length (horizontal), 0 without one
     * @param chordLength   the straight entry-to-exit distance, 0 without an entry and exit
     * @param routeLength   the route polyline's own length from the entry to the exit (the right angle at the node)
     * @param minClearance  the least clearance along the plaza line (capped at {@link Corridor#CAP}), 0 without one
     * @param curveFailure  why the curve was rejected (where and what), or null when it passed or was never built
     * @param expanded      cells the guided A* expanded (0 when it did not run)
     */
    public record Result(Kind kind, List<PlazaLine.Point> line, List<PlazaLine.Point> curve, NavPoint centre,
                         double radius, double entryAlong, double exitAlong, double lineLength, double chordLength,
                         double routeLength, double minClearance, String curveFailure, int expanded) {
        public Result {
            Objects.requireNonNull(kind, "kind");
            line = List.copyOf(line);
            curve = List.copyOf(curve);
        }

        /** Whether the trail would draw a plaza line here. */
        public boolean found() {
            return kind == Kind.CURVE || kind == Kind.GUIDED;
        }

        static Result without(Kind kind, NavPoint centre) {
            return new Result(kind, List.of(), List.of(), centre, 0, -1, -1, 0, 0, 0, 0, null, 0);
        }
    }

    /**
     * The plaza line where {@code route} crosses {@code plaza}.
     *
     * @param world the world, read while this runs
     * @param route the route polyline as feet positions (a floor point plus one block), in order; it must pass within
     *              2.5 blocks of the node
     * @param plaza the junction ({@link PlazaCrossing}: a designed plaza's radius, or 0 to measure); its centre is
     *              taken as its block's centre
     */
    public static Result cross(NavWorld world, List<NavPoint> route, PlazaCrossing plaza, Options options) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(plaza, "plaza");
        Objects.requireNonNull(options, "options");
        if (route.size() < 2 || !route.get(0).world().equals(plaza.centre().world())) {
            return Result.without(Kind.NOT_CROSSED, plaza.centre());
        }
        // the road graph gives a node as its floor block; its centre is where the route passes it (a block corner is
        // equally near the route points of three blocks round it)
        NavPoint node = plaza.centre();
        plaza = new PlazaCrossing(new NavPoint(node.world(), Math.floor(node.x()) + 0.5, node.y(),
            Math.floor(node.z()) + 0.5), plaza.radius());
        CorridorShaper.Options shaper = new CorridorShaper.Options(options.halfWidth(), options.clearance())
            .withPlazas(options.plazaBlocks(), options.clearance());
        long[] reads = new long[1];
        int at = nearest(route, plaza.centre());
        if (at < 0) {
            return Result.without(Kind.NOT_CROSSED, plaza.centre());
        }
        CorridorShaper.Plaza resolved = CorridorShaper.resolve(world, route, plaza, shaper, reads);
        if (resolved == null) {
            return Result.without(Kind.STREET, plaza.centre());
        }
        double cx = resolved.centre().x();
        double cz = resolved.centre().z();
        double radius = resolved.radius();
        if (!(radius > SAMPLE)) {
            return Result.without(Kind.STREET, plaza.centre());
        }
        double[] cumulative = cumulative(route);
        Crossing entry = crossing(route, cumulative, at, cx, cz, radius, -1);
        Crossing exit = crossing(route, cumulative, at, cx, cz, radius, +1);
        if (entry == null || exit == null) {
            return new Result(Kind.NOT_CROSSED, List.of(), List.of(), plaza.centre(), radius, -1, -1, 0, 0, 0, 0,
                null, 0);
        }
        double routeLength = exit.along() - entry.along();
        double chord = Math.hypot(exit.x() - entry.x(), exit.z() - entry.z());

        // the clearance map: the route round the plaza (a few blocks of each road) with the footprint as a disc
        List<NavPoint> near = dense(world, route, cumulative, entry.along() - ROAD_MARGIN, exit.along() + ROAD_MARGIN);
        if (near.isEmpty()) {
            near.add(route.get(at));
        }
        Corridor.Disc disc = new Corridor.Disc(cx, cz, radius + 1);
        Corridor corridor = Corridor.build(world, near, options.halfWidth(), false, List.of(disc));

        double clearance = options.clearance();
        List<PlazaLine.Point> curve = curve(corridor, entry, exit, cx, cz, options.pull());
        String failure = check(corridor, disc, curve, clearance);
        if (failure == null) {
            return new Result(Kind.CURVE, curve, curve, plaza.centre(), radius, entry.along(), exit.along(),
                length(curve), chord, routeLength, minClearance(corridor, curve), null, 0);
        }
        PlazaLine.Point from = curve.get(0);
        PlazaLine.Point to = curve.get(curve.size() - 1);
        PlazaLine.Result found = PlazaLine.search(corridor, from, to, disc, clearance, curve, options.guideCost());
        if (!found.found()) {
            return new Result(Kind.NONE, List.of(), curve, plaza.centre(), radius, entry.along(), exit.along(), 0,
                chord, routeLength, 0, failure, found.expanded());
        }
        return new Result(Kind.GUIDED, found.line(), curve, plaza.centre(), radius, entry.along(), exit.along(),
            length(found.line()), chord, routeLength, minClearance(corridor, found.line()), failure, found.expanded());
    }

    /**
     * The route from {@code from} to {@code to} blocks along it, with a point every block or less: its own points in
     * that stretch and points in between, each of these on the standable floor within a step or two of the last one
     * ({@link NavGeometry#floorNear}; one with no floor there is left out). The corridor is flooded from these cells and
     * takes the WorldGuard regions they stand in (the NPC rule: a region the route passes), so a road graph's sparse
     * points (a node, then the next bend 10 blocks on) do not leave a region the route crosses out.
     */
    static List<NavPoint> dense(NavWorld world, List<NavPoint> route, double[] cumulative, double from, double to) {
        List<NavPoint> out = new ArrayList<>();
        int floor = Integer.MIN_VALUE;
        for (int k = 1; k < route.size(); k++) {
            double s0 = cumulative[k - 1];
            double s1 = cumulative[k];
            if (s1 < from || s0 > to) {
                continue;
            }
            NavPoint a = route.get(k - 1);
            NavPoint b = route.get(k);
            if (floor == Integer.MIN_VALUE) {
                floor = a.blockY() - 1;
            }
            double len = s1 - s0;
            int steps = Math.max(1, (int) Math.ceil(len));
            for (int i = out.isEmpty() ? 0 : 1; i <= steps; i++) {
                double t = (double) i / steps;
                double along = s0 + len * t;
                if (along < from - 1e-9 || along > to + 1e-9) {
                    continue;
                }
                if (i == 0 || i == steps) {
                    NavPoint own = i == 0 ? a : b;
                    out.add(own);
                    floor = own.blockY() - 1;
                    continue;
                }
                NavPoint q = new NavPoint(a.world(), a.x() + (b.x() - a.x()) * t, floor, a.z() + (b.z() - a.z()) * t);
                NavPoint f = NavGeometry.floorNear(world, q, floor);
                if (f != null) {
                    out.add(NavGeometry.feet(f));
                    floor = f.blockY();
                }
            }
        }
        return out;
    }

    // ==================== the curve ====================

    /** Where the route crosses the footprint's edge: the point, its floor, the unit direction of travel there. */
    record Crossing(double x, double z, int floor, double tx, double tz, double along) {
    }

    /** The route point nearest the node within 2.5 blocks and two floors, as the NPC rule finds it; -1 if none. */
    static int nearest(List<NavPoint> route, NavPoint centre) {
        int at = -1;
        double atD = Double.MAX_VALUE;
        for (int k = 0; k < route.size(); k++) {
            NavPoint f = route.get(k);
            double d = f.horizontalDistance(centre);
            if (Math.abs(f.blockY() - 1 - centre.blockY()) <= 2 && d < atD) {
                atD = d;
                at = k;
            }
        }
        return atD <= 2.5 ? at : -1;
    }

    private static double[] cumulative(List<NavPoint> route) {
        double[] out = new double[route.size()];
        for (int k = 1; k < route.size(); k++) {
            out[k] = out[k - 1] + route.get(k - 1).horizontalDistance(route.get(k));
        }
        return out;
    }

    /**
     * Where the route, walked from the node's point {@code at} backwards ({@code dir} -1) or forwards (+1), first
     * crosses the circle of {@code radius} round the node: the exact point on that segment, with the direction of
     * travel (always the route's own direction). Null when the route ends inside the circle.
     */
    static Crossing crossing(List<NavPoint> route, double[] cumulative, int at, double cx, double cz, double radius,
                             int dir) {
        for (int k = at; k + dir >= 0 && k + dir < route.size(); k += dir) {
            NavPoint inside = route.get(k);
            NavPoint next = route.get(k + dir);
            if (Math.hypot(next.x() - cx, next.z() - cz) <= radius) {
                continue;
            }
            // solve |inside + s (next - inside) - c| = radius for s in [0, 1]
            double dx = next.x() - inside.x();
            double dz = next.z() - inside.z();
            double fx = inside.x() - cx;
            double fz = inside.z() - cz;
            double a = dx * dx + dz * dz;
            if (a < 1e-12) {
                continue;
            }
            double b = 2 * (fx * dx + fz * dz);
            double c = fx * fx + fz * fz - radius * radius;
            double disc = Math.max(0, b * b - 4 * a * c);
            double s = Math.max(0, Math.min(1, (-b + Math.sqrt(disc)) / (2 * a)));
            double x = inside.x() + s * dx;
            double z = inside.z() + s * dz;
            double len = Math.sqrt(a);
            // the direction of travel along the route: from the earlier point to the later one
            double tx = dir > 0 ? dx / len : -dx / len;
            double tz = dir > 0 ? dz / len : -dz / len;
            NavPoint floorOf = s < 0.5 ? inside : next;
            double along = dir > 0 ? cumulative[k] + s * len : cumulative[k] - s * len;
            return new Crossing(x, z, floorOf.blockY() - 1, tx, tz, along);
        }
        return null;
    }

    /**
     * The pulled curve (§ 8.3) sampled every {@value #SAMPLE} block (by parameter, densely enough), each sample with
     * the floor of its cell in the corridor (the nearer end's floor off the corridor, for the check to reject).
     */
    static List<PlazaLine.Point> curve(Corridor corridor, Crossing p0, Crossing p1, double nx, double nz, double pull) {
        double[][] c = controls(p0.x(), p0.z(), p0.tx(), p0.tz(), p1.x(), p1.z(), p1.tx(), p1.tz(), nx, nz, pull);
        double chord = Math.hypot(p1.x() - p0.x(), p1.z() - p0.z());
        // the control polygon bounds the curve's length
        double bound = Math.hypot(c[1][0] - c[0][0], c[1][1] - c[0][1]) + Math.hypot(c[2][0] - c[1][0], c[2][1] - c[1][1])
            + Math.hypot(c[3][0] - c[2][0], c[3][1] - c[2][1]);
        int steps = Math.max(2, (int) Math.ceil(Math.max(chord, bound) / SAMPLE));
        List<PlazaLine.Point> out = new ArrayList<>();
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double[] q = bezier(c, t);
            int floor;
            if (i == 0) {
                floor = p0.floor();
            } else if (i == steps) {
                floor = p1.floor();
            } else {
                int f = corridor.floorAt((int) Math.floor(q[0]), (int) Math.floor(q[1]));
                floor = f == Corridor.NONE ? (t < 0.5 ? p0.floor() : p1.floor()) : f;
            }
            out.add(new PlazaLine.Point(q[0], q[1], floor));
        }
        return out;
    }

    /**
     * The four control points of the pulled curve: P0, P0 + a·t0 + δ, P1 − a·t1 + δ, P1 with a = |P0P1| / 3 and δ
     * chosen so that the curve's midpoint B(½) lies at M + pull·(N − M), M the chord's midpoint. B(½) is
     * (P0 + 3 C0 + 3 C1 + P1) / 8, so moving both inner points by δ moves it by ¾ δ.
     */
    static double[][] controls(double x0, double z0, double tx0, double tz0, double x1, double z1, double tx1,
                               double tz1, double nx, double nz, double pull) {
        double a = Math.hypot(x1 - x0, z1 - z0) / 3;
        double c0x = x0 + a * tx0;
        double c0z = z0 + a * tz0;
        double c1x = x1 - a * tx1;
        double c1z = z1 - a * tz1;
        double bx = (x0 + 3 * c0x + 3 * c1x + x1) / 8;
        double bz = (z0 + 3 * c0z + 3 * c1z + z1) / 8;
        double mx = (x0 + x1) / 2;
        double mz = (z0 + z1) / 2;
        double targetX = mx + pull * (nx - mx);
        double targetZ = mz + pull * (nz - mz);
        double dx = (targetX - bx) * 4 / 3;
        double dz = (targetZ - bz) * 4 / 3;
        return new double[][] {{x0, z0}, {c0x + dx, c0z + dz}, {c1x + dx, c1z + dz}, {x1, z1}};
    }

    static double[] bezier(double[][] c, double t) {
        double u = 1 - t;
        double b0 = u * u * u;
        double b1 = 3 * u * u * t;
        double b2 = 3 * u * t * t;
        double b3 = t * t * t;
        return new double[] {
            b0 * c[0][0] + b1 * c[1][0] + b2 * c[2][0] + b3 * c[3][0],
            b0 * c[0][1] + b1 * c[1][1] + b2 * c[2][1] + b3 * c[3][1]};
    }

    // ==================== the check ====================

    /**
     * Null when the sampled curve fits the plaza (§ 8.4), else why not (the first failing sample, for the preview and
     * the log). The ends are the route's own points on the footprint's edge: their cells are not checked against the
     * footprint, and a sample near an end needs only that end's clearance plus its distance from it.
     */
    static String check(Corridor corridor, Corridor.Disc disc, List<PlazaLine.Point> curve, double clearance) {
        int n = curve.size();
        double[] along = new double[n];
        for (int k = 1; k < n; k++) {
            along[k] = along[k - 1] + Math.hypot(curve.get(k).x() - curve.get(k - 1).x(),
                curve.get(k).z() - curve.get(k - 1).z());
        }
        double total = along[n - 1];
        PlazaLine.Point first = curve.get(0);
        PlazaLine.Point last = curve.get(n - 1);
        double ca = corridor.clearanceAt(first.x(), first.z(), clearance);
        double cb = corridor.clearanceAt(last.x(), last.z(), clearance);
        int prev = first.floor();
        for (int k = 1; k < n - 1; k++) {
            PlazaLine.Point q = curve.get(k);
            int x = (int) Math.floor(q.x());
            int z = (int) Math.floor(q.z());
            String where = String.format(java.util.Locale.ROOT, "%.1f %.1f", q.x(), q.z());
            if (!corridor.contains(x, z)) {
                return "no walkable floor in the plaza's regions at " + where;
            }
            if (!disc.holds(q.x(), q.z())) {
                return "outside the plaza at " + where;
            }
            int f = corridor.floorAt(x, z);
            if (Math.abs(f - prev) > 1) {
                return "a step of more than a block at " + where;
            }
            for (int c = 0; c < 4; c++) {
                int bx = (int) Math.floor(q.x() + ((c & 1) == 0 ? -HALF_BODY : HALF_BODY));
                int bz = (int) Math.floor(q.z() + ((c & 2) == 0 ? -HALF_BODY : HALF_BODY));
                int bf = corridor.floorAt(bx, bz);
                if (bf == Corridor.NONE || Math.abs(bf - f) > 1) {
                    return "the body does not fit at " + where;
                }
            }
            double have = corridor.clearanceAt(q.x(), q.z(), clearance);
            double near = Math.min(ca + along[k], cb + (total - along[k]));
            double want = Math.min(clearance, near) - SLACK;
            boolean interior = along[k] > SAMPLE && total - along[k] > SAMPLE;
            if (interior && have < CorridorShaper.HARD_MINIMUM - 1e-9) {
                return String.format(java.util.Locale.ROOT, "%.2f from an obstacle (under the hard minimum %.2f) at %s",
                    have, CorridorShaper.HARD_MINIMUM, where);
            }
            if (have < want - 1e-9) {
                return String.format(java.util.Locale.ROOT, "%.2f from an obstacle (wanted %.2f) at %s", have,
                    want + SLACK, where);
            }
            prev = f;
        }
        return null;
    }

    private static double minClearance(Corridor corridor, List<PlazaLine.Point> line) {
        double least = Corridor.CAP;
        for (int k = 1; k < line.size(); k++) {
            PlazaLine.Point a = line.get(k - 1);
            PlazaLine.Point b = line.get(k);
            double len = Math.hypot(b.x() - a.x(), b.z() - a.z());
            int samples = Math.max(1, (int) Math.ceil(len / (SAMPLE / 2)));
            for (int i = 0; i <= samples; i++) {
                double t = (double) i / samples;
                least = Math.min(least, corridor.clearanceAt(a.x() + (b.x() - a.x()) * t, a.z() + (b.z() - a.z()) * t,
                    Corridor.CAP));
            }
        }
        return least;
    }

    /** The horizontal length of a line of points. */
    public static double length(List<PlazaLine.Point> line) {
        double total = 0;
        for (int k = 1; k < line.size(); k++) {
            total += Math.hypot(line.get(k).x() - line.get(k - 1).x(), line.get(k).z() - line.get(k - 1).z());
        }
        return total;
    }
}

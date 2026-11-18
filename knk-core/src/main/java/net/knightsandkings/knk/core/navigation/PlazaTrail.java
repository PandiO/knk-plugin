package net.knightsandkings.knk.core.navigation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import net.knightsandkings.knk.core.navigation.shape.PlazaCurve;
import net.knightsandkings.knk.core.navigation.shape.PlazaLine;

/**
 * The plaza lines of one route for the players' trail (off-road navigation phase 7 step 2, DESIGN § 8.2, A22 to A25;
 * PandiO/knk-workspace#712). The route, its length, the ETA, the maneuvers and the session's progress and cursor all stay
 * on the route polyline; only the drawn trail changes: a spot of the trail whose distance along the route falls inside
 * a plaza crossing is drawn at the same share of the way along the plaza line instead ({@link #draw}). So the particles
 * keep their fixed spots as the player walks, and the cursor's place on the route polyline is untouched (L4's note).
 *
 * <p>The crossings are worked out lazily, one at a time, when the trail's window comes near them ({@link #prepare}),
 * once for the route's life (§ 8.6): a crossing without a line keeps the route's own bend. Pure; the caller supplies
 * the line of a crossing (main thread, live world).
 */
public final class PlazaTrail {

    /** Blocks before a crossing's node, beyond the window's end, at which its line is worked out. */
    public static final double LOOK_AHEAD = 24;

    /**
     * A plaza crossing on the route, before or after its line is worked out.
     *
     * @param nodeId   the plaza's road node
     * @param nodeAlong where the route passes the node, in blocks along the route polyline
     */
    public record Crossing(int nodeId, double nodeAlong) {
    }

    /**
     * A plaza line placed on the route polyline.
     *
     * @param from   where it starts, in blocks along the route polyline (the route's own measure)
     * @param to     where it ends, after {@code from}
     * @param points the line, as trail points ({x, floor y, z}: block-centred x and z, the floor's y)
     */
    public record Segment(double from, double to, List<double[]> points) {
        public Segment {
            if (!(to > from)) {
                throw new IllegalArgumentException("a segment ends after it starts");
            }
            if (points.size() < 2) {
                throw new IllegalArgumentException("a segment needs a line");
            }
            points = List.copyOf(points);
        }

        /** The point the same share of the way along the line as {@code along} is from {@code from} to {@code to}. */
        double[] at(double along) {
            double share = Math.max(0, Math.min(1, (along - from) / (to - from)));
            double total = 0;
            double[] cum = new double[points.size()];
            for (int k = 1; k < points.size(); k++) {
                total += Math.hypot(points.get(k)[0] - points.get(k - 1)[0], points.get(k)[2] - points.get(k - 1)[2]);
                cum[k] = total;
            }
            double want = share * total;
            for (int k = 1; k < points.size(); k++) {
                if (cum[k] >= want - 1e-9) {
                    double len = cum[k] - cum[k - 1];
                    double t = len <= 1e-12 ? 0 : (want - cum[k - 1]) / len;
                    double[] a = points.get(k - 1);
                    double[] b = points.get(k);
                    return new double[] {a[0] + (b[0] - a[0]) * t, t < 0.5 ? a[1] : b[1], a[2] + (b[2] - a[2]) * t};
                }
            }
            return points.get(points.size() - 1).clone();
        }
    }

    private final List<Crossing> pending;
    private final List<Segment> segments = new ArrayList<>();
    private int computed;

    /** The trail of a route with these plaza crossings (in route order). */
    public PlazaTrail(List<Crossing> crossings) {
        this.pending = new ArrayList<>(Objects.requireNonNull(crossings, "crossings"));
    }

    /**
     * Works out the line of the first crossing not done yet whose node lies within the trail's window from
     * {@code along} for {@code length} blocks, plus {@link #LOOK_AHEAD}, or behind it (at most one per call, so the cost
     * is spread over the redraws). {@code line} gives a crossing's segment, or null when it has none (the route's own
     * bend stays).
     *
     * @return whether a crossing was worked out
     */
    public boolean prepare(double along, double length, Function<Crossing, Segment> line) {
        for (int i = 0; i < pending.size(); i++) {
            Crossing c = pending.get(i);
            if (c.nodeAlong() <= along + length + LOOK_AHEAD) {
                pending.remove(i);
                computed++;
                Segment s = line.apply(c);
                if (s != null) {
                    segments.add(s);
                }
                return true;
            }
        }
        return false;
    }

    /** Crossings worked out so far (with or without a line). */
    public int computed() {
        return computed;
    }

    /** The plaza lines placed so far. */
    public List<Segment> segments() {
        return List.copyOf(segments);
    }

    /**
     * The trail's spots with the ones inside a plaza crossing moved onto its line.
     *
     * @param spots  the trail points as drawn without plaza lines
     * @param alongs each spot's distance along the route polyline
     */
    public List<double[]> draw(List<double[]> spots, double[] alongs) {
        if (spots.size() != alongs.length) {
            throw new IllegalArgumentException("one along per spot");
        }
        if (segments.isEmpty()) {
            return spots;
        }
        List<double[]> out = new ArrayList<>(spots.size());
        for (int k = 0; k < spots.size(); k++) {
            double[] moved = null;
            for (Segment s : segments) {
                if (alongs[k] >= s.from() - 1e-9 && alongs[k] <= s.to() + 1e-9) {
                    moved = s.at(alongs[k]);
                    break;
                }
            }
            out.add(moved == null ? spots.get(k) : moved);
        }
        return out;
    }

    /**
     * A crossing's plaza line as a segment of the route polyline ({@code floorPoints}: the route's floor points, as
     * {@code Route.polyline()} gives them; its measure is 3D, as the route's own), or null when the crossing has no
     * line. {@code result} was worked out over the same polyline's feet (block centres), so its horizontal distances
     * along it map onto the polyline's segments.
     */
    public static Segment segmentOf(List<double[]> floorPoints, PlazaCurve.Result result) {
        if (!result.found() || result.entryAlong() < 0 || result.exitAlong() <= result.entryAlong()) {
            return null;
        }
        double from = along3d(floorPoints, result.entryAlong());
        double to = along3d(floorPoints, result.exitAlong());
        if (!(to > from)) {
            return null;
        }
        List<double[]> points = new ArrayList<>();
        for (PlazaLine.Point p : result.line()) {
            points.add(new double[] {p.x(), p.floor(), p.z()});
        }
        return points.size() < 2 ? null : new Segment(from, to, points);
    }

    /** A horizontal distance along a polyline as the polyline's own 3D distance to the same place. */
    static double along3d(List<double[]> points, double horizontal) {
        double h = 0;
        double d = 0;
        for (int k = 1; k < points.size(); k++) {
            double[] a = points.get(k - 1);
            double[] b = points.get(k);
            double hl = Math.hypot(b[0] - a[0], b[2] - a[2]);
            double dl = Math.sqrt(hl * hl + (b[1] - a[1]) * (b[1] - a[1]));
            if (h + hl >= horizontal - 1e-9) {
                double t = hl <= 1e-12 ? 0 : (horizontal - h) / hl;
                return d + Math.max(0, Math.min(1, t)) * dl;
            }
            h += hl;
            d += dl;
        }
        return d;
    }
}

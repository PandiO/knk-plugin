package net.knightsandkings.knk.core.roads.route;

import net.knightsandkings.knk.core.domain.roads.RoadClass;
import net.knightsandkings.knk.core.domain.roads.RoadEdge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A computed route (DESIGN §6.2 step 5, plan 2d "router output for Phase 4"): the ordered edge
 * steps with the entry/exit position on each (partial on the first and last), the walked length,
 * the routing cost, the polyline to draw (floor-block positions from the start snap point to the
 * end snap point) and each step's {@link EdgeVerdict} (pass-through hints). Immutable.
 *
 * <p>A route may have no steps (start and goal coincide, or an explanation's partial route that
 * cannot leave the start): its polyline is then the single start point.
 */
public final class Route {

    /**
     * One edge of the route.
     *
     * @param edge          the edge
     * @param forward       walked From → To?
     * @param entryAlong    polyline distance from the From node where the route enters the edge
     * @param exitAlong     ... and where it leaves it ({@code < entryAlong} when backward)
     * @param length        walked length of this stretch (fraction of the edge's walked length)
     * @param cost          routing cost of this stretch ({@code length × cost factor})
     * @param startDistance route length before this step
     * @param verdict       the access verdict the route was computed with
     */
    public record Step(RoadEdge edge, boolean forward, double entryAlong, double exitAlong, double length, double cost,
                       double startDistance, EdgeVerdict verdict) {
        public Step {
            Objects.requireNonNull(edge, "edge");
            Objects.requireNonNull(verdict, "verdict");
        }

        /** The node the step starts at, or {@code -1} when it starts mid-edge. */
        public int entryNode(EdgePolyline polyline) {
            if (entryAlong <= 0) {
                return edge.fromNodeId();
            }
            return entryAlong >= polyline.length() ? edge.toNodeId() : -1;
        }

        /** The node the step ends at, or {@code -1} when it ends mid-edge. */
        public int exitNode(EdgePolyline polyline) {
            if (exitAlong <= 0) {
                return edge.fromNodeId();
            }
            return exitAlong >= polyline.length() ? edge.toNodeId() : -1;
        }

        public Step withVerdict(EdgeVerdict v) {
            return new Step(edge, forward, entryAlong, exitAlong, length, cost, startDistance, v);
        }

        /** An un-normalised step for {@link Route#build} (lengths and costs are recomputed there). */
        public static Step of(RoadEdge edge, boolean forward, double entryAlong, double exitAlong, EdgeVerdict verdict) {
            return new Step(edge, forward, entryAlong, exitAlong, 0, 0, 0, verdict);
        }
    }

    /** The player's projection onto the route: the nearest polyline point and its route distance. */
    public record Projection(double along, double distance, double[] point) {
    }

    private final List<Step> steps;
    private final List<double[]> polyline;
    private final double[] cumulative;
    private final double length;
    private final double cost;
    private final SnapPoint start;
    private final SnapPoint end;
    private final List<Integer> nodeIds;
    private final Map<RoadClass, Double> classCost;

    private Route(List<Step> steps, List<double[]> polyline, double length, double cost, SnapPoint start, SnapPoint end,
                  List<Integer> nodeIds, Map<RoadClass, Double> classCost) {
        this.steps = Collections.unmodifiableList(steps);
        this.polyline = Collections.unmodifiableList(polyline);
        this.cumulative = new double[polyline.size()];
        for (int i = 1; i < polyline.size(); i++) {
            cumulative[i] = cumulative[i - 1] + EdgePolyline.distance(polyline.get(i - 1), polyline.get(i));
        }
        this.length = length;
        this.cost = cost;
        this.start = start;
        this.end = end;
        this.nodeIds = Collections.unmodifiableList(nodeIds);
        this.classCost = classCost;
    }

    /**
     * Builds a route from its steps: the polyline is concatenated from the edges' polylines, the
     * step lengths, costs and start distances recomputed from the snapshot and the class costs.
     */
    public static Route build(RoadNetworkSnapshot snapshot, SnapPoint start, SnapPoint end, List<Step> steps,
                              Map<RoadClass, Double> classCost) {
        List<Step> normalised = new ArrayList<>(steps.size());
        List<double[]> polyline = new ArrayList<>();
        List<Integer> nodes = new ArrayList<>();
        double distance = 0;
        double cost = 0;
        polyline.add(start.point().clone());
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            EdgePolyline p = snapshot.polyline(s.edge());
            double fraction = p.length() <= 0 ? 0 : Math.abs(s.exitAlong() - s.entryAlong()) / p.length();
            double stepLength = fraction * s.edge().length();
            double stepCost = stepLength * snapshot.costFactor(s.edge(), classCost);
            normalised.add(new Step(s.edge(), s.forward(), s.entryAlong(), s.exitAlong(), stepLength, stepCost, distance,
                s.verdict()));
            distance += stepLength;
            cost += stepCost;
            for (double[] pt : p.subPolyline(s.entryAlong(), s.exitAlong())) {
                append(polyline, pt);
            }
            if (i + 1 < steps.size()) {
                int exitNode = s.exitNode(p);
                if (exitNode >= 0) {
                    nodes.add(exitNode);
                }
            }
        }
        append(polyline, end.point().clone());
        return new Route(normalised, polyline, distance, cost, start, end, nodes, Map.copyOf(classCost));
    }

    /** A route without steps: the start point only. */
    public static Route empty(SnapPoint start) {
        return new Route(List.of(), List.of(start.point().clone()), 0, 0, start, start, List.of(), Map.of());
    }

    private static void append(List<double[]> polyline, double[] pt) {
        if (polyline.isEmpty() || !samePoint(polyline.get(polyline.size() - 1), pt)) {
            polyline.add(pt);
        }
    }

    private static boolean samePoint(double[] a, double[] b) {
        return Math.abs(a[0] - b[0]) < 1e-9 && Math.abs(a[1] - b[1]) < 1e-9 && Math.abs(a[2] - b[2]) < 1e-9;
    }

    public List<Step> steps() {
        return steps;
    }

    public boolean isEmpty() {
        return steps.isEmpty();
    }

    /** Floor-block points from the start snap point to the end snap point. */
    public List<double[]> polyline() {
        return polyline;
    }

    /** Walked length in blocks (sum of the steps). */
    public double length() {
        return length;
    }

    /** Polyline length (≤ {@link #length()}); what {@link #project} and {@link #pointAt} measure in. */
    public double polylineLength() {
        return cumulative[cumulative.length - 1];
    }

    public double cost() {
        return cost;
    }

    public SnapPoint start() {
        return start;
    }

    public SnapPoint end() {
        return end;
    }

    /** The real node ids between consecutive steps, in order (the start's and end's nodes excluded). */
    public List<Integer> nodeIds() {
        return nodeIds;
    }

    /** The steps whose verdict is PASS_THROUGH (the hints to show). */
    public List<Step> passThroughSteps() {
        List<Step> hints = new ArrayList<>();
        for (Step s : steps) {
            if (s.verdict().isPassThrough()) {
                hints.add(s);
            }
        }
        return hints;
    }

    /** The first {@code count} steps as a new route ending where step {@code count - 1} exits. */
    public Route truncated(RoadNetworkSnapshot snapshot, int count) {
        if (count <= 0) {
            return empty(start);
        }
        if (count >= steps.size()) {
            return this;
        }
        List<Step> head = new ArrayList<>(steps.subList(0, count));
        Step last = head.get(count - 1);
        SnapPoint newEnd = SnapPoint.onEdge(snapshot, last.edge().id(), last.exitAlong());
        return build(snapshot, start, newEnd, head, classCost);
    }

    /** Same steps with verdicts from another policy (the explainer's partial route). */
    public Route withVerdicts(RoadNetworkSnapshot snapshot, AccessPolicy policy) {
        List<Step> re = new ArrayList<>(steps.size());
        for (Step s : steps) {
            re.add(s.withVerdict(policy.check(s.edge())));
        }
        return build(snapshot, start, end, re, classCost);
    }

    /** The point {@code along} polyline blocks from the start (clamped). */
    public double[] pointAt(double along) {
        if (along <= 0 || polyline.size() == 1) {
            return polyline.get(0).clone();
        }
        double total = polylineLength();
        if (along >= total) {
            return polyline.get(polyline.size() - 1).clone();
        }
        int i = 0;
        while (i + 1 < cumulative.length - 1 && cumulative[i + 1] <= along) {
            i++;
        }
        double segLen = cumulative[i + 1] - cumulative[i];
        double t = segLen <= 0 ? 0 : (along - cumulative[i]) / segLen;
        double[] a = polyline.get(i);
        double[] b = polyline.get(i + 1);
        return new double[] {a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
    }

    /**
     * Nearest point of the polyline to a <b>floor</b> position, considering only segments that end
     * at or after {@code minAlong} (progress never runs backwards past the look-back a caller
     * subtracts). 3D, unweighted.
     */
    public Projection project(double x, double y, double z, double minAlong) {
        if (polyline.size() == 1) {
            double[] p = polyline.get(0);
            return new Projection(0, dist(p, x, y, z), p.clone());
        }
        double bestD2 = Double.POSITIVE_INFINITY;
        double bestAlong = 0;
        double[] bestPoint = polyline.get(0);
        for (int i = 0; i + 1 < polyline.size(); i++) {
            if (cumulative[i + 1] < minAlong) {
                continue;
            }
            double[] a = polyline.get(i);
            double[] b = polyline.get(i + 1);
            double dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
            double len2 = dx * dx + dy * dy + dz * dz;
            double t = 0;
            if (len2 > 0) {
                t = Math.max(0, Math.min(1, ((x - a[0]) * dx + (y - a[1]) * dy + (z - a[2]) * dz) / len2));
            }
            double px = a[0] + dx * t, py = a[1] + dy * t, pz = a[2] + dz * t;
            double d2 = (px - x) * (px - x) + (py - y) * (py - y) + (pz - z) * (pz - z);
            if (d2 < bestD2) {
                bestD2 = d2;
                bestAlong = cumulative[i] + t * Math.sqrt(len2);
                bestPoint = new double[] {px, py, pz};
            }
        }
        return new Projection(bestAlong, Math.sqrt(bestD2), bestPoint);
    }

    private static double dist(double[] p, double x, double y, double z) {
        return Math.sqrt((p[0] - x) * (p[0] - x) + (p[1] - y) * (p[1] - y) + (p[2] - z) * (p[2] - z));
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Route{length=").append(String.format("%.1f", length)).append(", edges=[");
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            sb.append(i > 0 ? ", " : "").append(s.forward() ? "" : "-").append(s.edge().id());
        }
        return sb.append("]}").toString();
    }
}

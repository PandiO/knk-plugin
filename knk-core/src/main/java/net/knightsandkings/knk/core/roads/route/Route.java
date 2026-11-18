package net.knightsandkings.knk.core.roads.route;

import net.knightsandkings.knk.core.domain.roads.RoadClass;
import net.knightsandkings.knk.core.domain.roads.RoadEdge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
                       double startDistance, EdgeVerdict verdict) implements Piece {
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

    /**
     * A piece of a route as the router hands it to {@link #build(RoadNetworkSnapshot, SnapPoint, SnapPoint, List, Map)}:
     * a road {@link Step}, or a {@link LinkStep} (off-road navigation phase 2, DESIGN § 5.1, a walk link between two road
     * nodes, behind {@code navigation.walk-links}).
     */
    public sealed interface Piece permits Step, LinkStep {
    }

    /**
     * A walk link walked from its From node to its To node (DESIGN § 5.2).
     *
     * @param link the link
     * @param cost its routing cost (what the router added for it)
     */
    public record LinkStep(WalkLink link, double cost) implements Piece {
        public LinkStep {
            Objects.requireNonNull(link, "link");
        }
    }

    /**
     * Where a walk link lies on the route (DESIGN § 5.1: the route as a list of legs).
     *
     * @param beforeStep    index of the road step that follows the link ({@code steps().size()} when none follows)
     * @param link          the link
     * @param polylineFrom  polyline distance where the link starts (what {@link #project} measures)
     * @param polylineTo    ... and where it ends
     * @param startDistance route length before the link
     * @param cost          its routing cost
     */
    public record WalkLinkStretch(int beforeStep, WalkLink link, double polylineFrom, double polylineTo,
                                  double startDistance, double cost) {
    }

    /** One leg of the route's own list (DESIGN § 5.1): a stretch on roads or a walk link, in route order. */
    public sealed interface Leg permits RoadLeg, WalkLinkLeg {
        /** Polyline distance where the leg starts. */
        double polylineFrom();

        /** Polyline distance where the leg ends. */
        double polylineTo();
    }

    /** Road steps {@code [firstStep, endStep)}. */
    public record RoadLeg(double polylineFrom, double polylineTo, int firstStep, int endStep) implements Leg {
    }

    /** A walk link. */
    public record WalkLinkLeg(double polylineFrom, double polylineTo, WalkLinkStretch stretch) implements Leg {
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
    /** Phase 2 (#705): the walk links on the route, in order; empty for a road-only route. */
    private final List<WalkLinkStretch> walkLinks;
    /** Polyline distance where each step starts; null for a road-only route (its steps follow each other). */
    private final double[] stepStarts;

    private Route(List<Step> steps, List<double[]> polyline, double length, double cost, SnapPoint start, SnapPoint end,
                  List<Integer> nodeIds, Map<RoadClass, Double> classCost) {
        this(steps, polyline, length, cost, start, end, nodeIds, classCost, List.of(), null);
    }

    private Route(List<Step> steps, List<double[]> polyline, double length, double cost, SnapPoint start, SnapPoint end,
                  List<Integer> nodeIds, Map<RoadClass, Double> classCost, List<WalkLinkStretch> walkLinks,
                  double[] stepStarts) {
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
        this.walkLinks = List.copyOf(walkLinks);
        this.stepStarts = stepStarts;
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

    /**
     * Phase 2 (#705, DESIGN § 5.1): a route of road steps and walk links in any order. Road steps are normalised as by
     * {@link #build(RoadNetworkSnapshot, SnapPoint, SnapPoint, List, Map)}; a link adds its path to the polyline, its
     * length to the route's and its routing cost to the cost. Without a {@link LinkStep} it is that method's route.
     */
    public static Route buildPieces(RoadNetworkSnapshot snapshot, SnapPoint start, SnapPoint end, List<? extends Piece> pieces,
                                    Map<RoadClass, Double> classCost) {
        if (pieces.stream().noneMatch(p -> p instanceof LinkStep)) {
            List<Step> steps = new ArrayList<>(pieces.size());
            pieces.forEach(p -> steps.add((Step) p));
            return build(snapshot, start, end, steps, classCost);
        }
        List<Step> normalised = new ArrayList<>();
        List<double[]> polyline = new ArrayList<>();
        List<Integer> nodes = new ArrayList<>();
        List<int[]> stepRanges = new ArrayList<>(); // polyline index where each step starts
        List<Object[]> linkRanges = new ArrayList<>(); // {beforeStep, link, fromIndex, toIndex, startDistance, cost}
        double distance = 0;
        double cost = 0;
        polyline.add(start.point().clone());
        for (int i = 0; i < pieces.size(); i++) {
            Piece piece = pieces.get(i);
            int fromIndex = polyline.size() - 1;
            boolean more = i + 1 < pieces.size();
            if (piece instanceof Step s) {
                EdgePolyline p = snapshot.polyline(s.edge());
                double fraction = p.length() <= 0 ? 0 : Math.abs(s.exitAlong() - s.entryAlong()) / p.length();
                double stepLength = fraction * s.edge().length();
                double stepCost = stepLength * snapshot.costFactor(s.edge(), classCost);
                normalised.add(new Step(s.edge(), s.forward(), s.entryAlong(), s.exitAlong(), stepLength, stepCost,
                    distance, s.verdict()));
                stepRanges.add(new int[] {fromIndex});
                distance += stepLength;
                cost += stepCost;
                for (double[] pt : p.subPolyline(s.entryAlong(), s.exitAlong())) {
                    append(polyline, pt);
                }
                if (more) {
                    int exitNode = s.exitNode(p);
                    if (exitNode >= 0) {
                        nodes.add(exitNode);
                    }
                }
            } else {
                LinkStep l = (LinkStep) piece;
                double startDistance = distance;
                distance += l.link().length();
                cost += l.cost();
                for (double[] pt : l.link().path()) {
                    append(polyline, pt.clone());
                }
                linkRanges.add(new Object[] {normalised.size(), l.link(), fromIndex, polyline.size() - 1, startDistance,
                    l.cost()});
                if (more) {
                    nodes.add(l.link().toNode());
                }
            }
        }
        append(polyline, end.point().clone());
        double[] cumulative = new double[polyline.size()];
        for (int i = 1; i < polyline.size(); i++) {
            cumulative[i] = cumulative[i - 1] + EdgePolyline.distance(polyline.get(i - 1), polyline.get(i));
        }
        double[] stepStarts = new double[stepRanges.size()];
        for (int i = 0; i < stepStarts.length; i++) {
            stepStarts[i] = cumulative[stepRanges.get(i)[0]];
        }
        List<WalkLinkStretch> links = new ArrayList<>(linkRanges.size());
        for (Object[] r : linkRanges) {
            links.add(new WalkLinkStretch((Integer) r[0], (WalkLink) r[1], cumulative[(Integer) r[2]],
                cumulative[(Integer) r[3]], (Double) r[4], (Double) r[5]));
        }
        return new Route(normalised, polyline, distance, cost, start, end, nodes, Map.copyOf(classCost), links,
            stepStarts);
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
        return steps.isEmpty() && walkLinks.isEmpty();
    }

    /** Phase 2 (#705): the walk links on the route, in route order; empty for a road-only route (every route with the flag off). */
    public List<WalkLinkStretch> walkLinks() {
        return walkLinks;
    }

    public boolean hasWalkLinks() {
        return !walkLinks.isEmpty();
    }

    /** The walk link at a polyline distance, if the route walks one there. */
    public Optional<WalkLinkStretch> walkLinkAt(double polylineAlong) {
        for (WalkLinkStretch w : walkLinks) {
            if (polylineAlong >= w.polylineFrom() - 1e-9 && polylineAlong <= w.polylineTo() + 1e-9) {
                return Optional.of(w);
            }
        }
        return Optional.empty();
    }

    /**
     * Polyline distance where step {@code i} starts. On a road-only route the steps follow each other: the sum of the
     * earlier steps' stretches, as the D13 re-check has always counted.
     */
    public double stepPolylineStart(int i) {
        if (stepStarts != null) {
            return stepStarts[i];
        }
        double at = 0;
        for (int k = 0; k < i; k++) {
            at += Math.abs(steps.get(k).exitAlong() - steps.get(k).entryAlong());
        }
        return at;
    }

    /**
     * The route as its list of legs (DESIGN § 5.1): road stretches and walk links in order. A road-only route is one
     * road leg (none when it has no steps). The walk to the road and the last walk after it are the runtime's legs
     * around this list.
     */
    public List<Leg> legs() {
        List<Leg> out = new ArrayList<>();
        if (walkLinks.isEmpty()) {
            if (!steps.isEmpty()) {
                out.add(new RoadLeg(0, polylineLength(), 0, steps.size()));
            }
            return out;
        }
        int step = 0;
        for (WalkLinkStretch w : walkLinks) {
            if (w.beforeStep() > step) {
                out.add(new RoadLeg(stepPolylineStart(step), w.polylineFrom(), step, w.beforeStep()));
                step = w.beforeStep();
            }
            out.add(new WalkLinkLeg(w.polylineFrom(), w.polylineTo(), w));
        }
        if (step < steps.size()) {
            out.add(new RoadLeg(stepPolylineStart(step), polylineLength(), step, steps.size()));
        }
        return out;
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
        if (!walkLinks.isEmpty()) {
            return buildPieces(snapshot, start, newEnd, pieces(head, count), classCost);
        }
        return build(snapshot, start, newEnd, head, classCost);
    }

    /** Same steps with verdicts from another policy (the explainer's partial route). */
    public Route withVerdicts(RoadNetworkSnapshot snapshot, AccessPolicy policy) {
        List<Step> re = new ArrayList<>(steps.size());
        for (Step s : steps) {
            re.add(s.withVerdict(policy.check(s.edge())));
        }
        if (!walkLinks.isEmpty()) {
            return buildPieces(snapshot, start, end, pieces(re, re.size()), classCost);
        }
        return build(snapshot, start, end, re, classCost);
    }

    /** {@code steps} (this route's, re-verdicted) with this route's walk links before step {@code endStep} put back. */
    private List<Piece> pieces(List<Step> steps, int endStep) {
        List<Piece> out = new ArrayList<>();
        int link = 0;
        for (int i = 0; i < endStep; i++) {
            while (link < walkLinks.size() && walkLinks.get(link).beforeStep() == i) {
                out.add(new LinkStep(walkLinks.get(link).link(), walkLinks.get(link).cost()));
                link++;
            }
            out.add(steps.get(i));
        }
        return out;
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
        int link = 0;
        for (int i = 0; i < steps.size(); i++) {
            while (link < walkLinks.size() && walkLinks.get(link).beforeStep() == i) {
                sb.append(i > 0 || link > 0 ? ", " : "").append("~").append(walkLinks.get(link++).link().id());
            }
            Step s = steps.get(i);
            sb.append(i > 0 || link > 0 ? ", " : "").append(s.forward() ? "" : "-").append(s.edge().id());
        }
        while (link < walkLinks.size()) {
            sb.append(!steps.isEmpty() || link > 0 ? ", " : "").append("~").append(walkLinks.get(link++).link().id());
        }
        return sb.append("]}").toString();
    }
}

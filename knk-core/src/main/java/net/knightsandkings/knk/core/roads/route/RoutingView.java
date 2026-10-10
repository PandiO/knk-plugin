package net.knightsandkings.knk.core.roads.route;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;

/**
 * The network navigation routes on (rev. 7 Part A, REV7_PROPOSAL §2): every stored edge cut where its
 * access tags change - at a gate door, at the border of a region - so each piece carries only the tags of
 * the stretch it covers. A gate is then its own short piece between two {@link RoadNodeKind#SPLIT} nodes,
 * and a road that clips a district is blocked only inside the district. The router, the explainer and the
 * re-check stay whole-edge: on the view an edge is one access situation.
 *
 * <p>Pieces keep their stored edge's profile, street, flags, source and cost multiplier; their walked length
 * is the stored length scaled to their share of the polyline. Their ids (and the split nodes') start at
 * {@link #FIRST_SYNTHETIC_ID}, above any stored id and clear of the router's negative virtual nodes; the
 * snapshot maps them back ({@link RoadNetworkSnapshot#storedEdgeId}, {@link RoadNetworkSnapshot#piece}).
 *
 * <p>The tags come from the live world (knk-paper {@code LiveEdgeTags}): a {@link Hit} per sample, with its
 * position along the polyline. Cuts fall on sample positions, widened by one sample on each side, so a
 * region's piece starts just outside its border and a door's piece just before the door (D5: regions are
 * sampled every 2 blocks, doors every half block). A stored tag the samples never find (a region deleted
 * since the build) stays on the whole edge, as before the view. Pure and Bukkit-free.
 *
 * <p>KNG-110 (P4): where a region covers only part of the road's width, a sample also carries the road's
 * {@linkplain RoadEdge#lanes lanes} - the region sets of the road cells across it, so the entry rule can look for a
 * free gap. Lanes cut the road like tags: a piece has one set of lanes, or none (one lane, the centre line's regions).
 */
public final class RoutingView {

    /** The first id of a piece or a split node; stored ids must stay below it. */
    public static final int FIRST_SYNTHETIC_ID = 2_000_000_000;
    /** Cuts closer together than this (blocks) become one. */
    static final double MIN_GAP = 0.25;

    /** The tags found at one sample of an edge. @param along polyline position of the sample */
    public record Hit<T>(double along, Set<T> tags) {
        public Hit {
            tags = Set.copyOf(tags);
        }
    }

    /**
     * A stretch of a stored edge with one set of access tags, {@code from < to} in polyline blocks.
     *
     * @param lanes the region sets across the road where a region covers part of its width (KNG-110), else empty
     */
    public record Span(double from, double to, List<String> regionIds, List<Integer> gateDoorIds,
                       List<List<String>> lanes) {
        public Span {
            regionIds = List.copyOf(regionIds);
            gateDoorIds = List.copyOf(gateDoorIds);
            lanes = lanes.stream().<List<String>>map(List::copyOf).toList();
        }

        /** A span with one lane, the centre line's. */
        public Span(double from, double to, List<String> regionIds, List<Integer> gateDoorIds) {
            this(from, to, regionIds, gateDoorIds, List.of());
        }
    }

    private RoutingView() {
    }

    /**
     * The lanes of a road's cross-section: the region set of each road cell, without the sets that hold another one
     * (a cell in more regions is never the easier way through), each sorted, smallest first.
     */
    public static List<List<String>> minimalLanes(Collection<? extends Collection<String>> cells) {
        List<Set<String>> sets = new ArrayList<>();
        for (Collection<String> cell : cells) {
            Set<String> set = Set.copyOf(cell);
            if (!sets.contains(set)) {
                sets.add(set);
            }
        }
        List<List<String>> out = new ArrayList<>();
        for (Set<String> set : sets) {
            boolean holdsAnother = sets.stream().anyMatch(other -> other.size() < set.size() && set.containsAll(other));
            if (!holdsAnother) {
                out.add(set.stream().sorted().toList());
            }
        }
        out.sort(Comparator.<List<String>>comparingInt(List::size).thenComparing(Object::toString));
        return out;
    }

    /**
     * The edge's stretches by access tags, From → To, covering {@code [0, length]}; one span when nothing
     * changes along it. Stored tags come first in each span's lists, then new ones in the order the edge meets
     * them.
     *
     * @param edge    the stored edge (its stored tags)
     * @param length  its polyline length
     * @param regions region hits, in polyline order (feet-level WorldGuard regions per sample)
     * @param doors   gate door hits, in polyline order
     */
    public static List<Span> spans(RoadEdge edge, double length, List<Hit<String>> regions, List<Hit<Integer>> doors) {
        return spans(edge, length, regions, List.of(), doors);
    }

    /**
     * As {@link #spans(RoadEdge, double, List, List)}, with the lanes found across the road (KNG-110): a hit's tags
     * are the sample's {@link #minimalLanes}, or none where the centre line decides. A stored region found nowhere
     * joins every lane of a span, as it stays on the span.
     *
     * @param lanes lane hits, in polyline order (may be empty: no lanes anywhere)
     */
    public static List<Span> spans(RoadEdge edge, double length, List<Hit<String>> regions,
                                   List<Hit<List<String>>> lanes, List<Hit<Integer>> doors) {
        Objects.requireNonNull(edge, "edge");
        Map<String, List<double[]>> regionRuns = runs(regions, length);
        Map<List<String>, List<double[]>> laneRuns = runs(lanes, length);
        Map<Integer, List<double[]>> doorRuns = runs(doors, length);

        List<Double> cuts = new ArrayList<>();
        regionRuns.values().forEach(list -> list.forEach(r -> addCut(cuts, r, length)));
        laneRuns.values().forEach(list -> list.forEach(r -> addCut(cuts, r, length)));
        doorRuns.values().forEach(list -> list.forEach(r -> addCut(cuts, r, length)));
        cuts.sort(Double::compare);
        List<Double> bounds = new ArrayList<>();
        bounds.add(0.0);
        for (double c : cuts) {
            if (c - bounds.get(bounds.size() - 1) >= MIN_GAP && length - c >= MIN_GAP) {
                bounds.add(c);
            }
        }
        bounds.add(length);

        List<Span> out = new ArrayList<>();
        for (int i = 0; i + 1 < bounds.size(); i++) {
            double from = bounds.get(i);
            double to = bounds.get(i + 1);
            double mid = (from + to) / 2;
            List<String> r = tagsAt(edge.regionIds(), regionRuns, mid);
            List<Integer> d = tagsAt(edge.gateDoorIds(), doorRuns, mid);
            List<List<String>> l = lanesAt(edge.regionIds(), regionRuns, laneRuns, mid);
            Span last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && Set.copyOf(last.regionIds()).equals(Set.copyOf(r))
                && Set.copyOf(last.gateDoorIds()).equals(Set.copyOf(d)) && last.lanes().equals(l)) {
                out.set(out.size() - 1, new Span(last.from(), to, last.regionIds(), last.gateDoorIds(), last.lanes()));
            } else {
                out.add(new Span(from, to, r, d, l));
            }
        }
        return out;
    }

    /** The lanes found here, each with the stored regions the samples never find; empty when none are found. */
    private static List<List<String>> lanesAt(List<String> stored, Map<String, List<double[]>> regionRuns,
                                              Map<List<String>, List<double[]>> laneRuns, double at) {
        List<Set<String>> here = new ArrayList<>();
        for (Map.Entry<List<String>, List<double[]>> e : laneRuns.entrySet()) {
            if (covers(e.getValue(), at)) {
                Set<String> lane = new LinkedHashSet<>(e.getKey());
                stored.stream().filter(tag -> !regionRuns.containsKey(tag)).forEach(lane::add);
                here.add(lane);
            }
        }
        return here.isEmpty() ? List.of() : minimalLanes(here);
    }

    /** Per tag, the stretches it covers: each run of samples with it, widened to the samples either side. */
    private static <T> Map<T, List<double[]>> runs(List<Hit<T>> hits, double length) {
        Map<T, List<double[]>> out = new LinkedHashMap<>(); // keys in the order the edge meets them
        Map<T, Integer> open = new HashMap<>();
        for (int i = 0; i < hits.size(); i++) {
            for (T tag : hits.get(i).tags()) {
                if (open.putIfAbsent(tag, i) == null) {
                    out.computeIfAbsent(tag, k -> new ArrayList<>());
                }
            }
            for (var it = open.entrySet().iterator(); it.hasNext(); ) {
                var e = it.next();
                if (!hits.get(i).tags().contains(e.getKey())) {
                    out.get(e.getKey()).add(run(hits, e.getValue(), i - 1, length));
                    it.remove();
                }
            }
        }
        for (var e : open.entrySet()) {
            out.get(e.getKey()).add(run(hits, e.getValue(), hits.size() - 1, length));
        }
        return out;
    }

    private static double[] run(List<? extends Hit<?>> hits, int first, int last, double length) {
        double from = first == 0 ? 0 : hits.get(first - 1).along();
        double to = last == hits.size() - 1 ? length : hits.get(last + 1).along();
        return new double[] {Math.max(0, from), Math.min(length, to)};
    }

    private static void addCut(List<Double> cuts, double[] run, double length) {
        if (run[0] > 0) {
            cuts.add(run[0]);
        }
        if (run[1] < length) {
            cuts.add(run[1]);
        }
    }

    /** Stored tags found here or found nowhere (stored order), then the new tags found here. */
    private static <T> List<T> tagsAt(List<T> stored, Map<T, List<double[]>> runs, double at) {
        Set<T> out = new LinkedHashSet<>();
        for (T tag : stored) {
            if (!runs.containsKey(tag) || covers(runs.get(tag), at)) {
                out.add(tag);
            }
        }
        for (Map.Entry<T, List<double[]>> e : runs.entrySet()) {
            if (covers(e.getValue(), at)) {
                out.add(e.getKey());
            }
        }
        return new ArrayList<>(out);
    }

    private static boolean covers(List<double[]> list, double at) {
        for (double[] r : list) {
            if (r[0] <= at && at <= r[1]) {
                return true;
            }
        }
        return false;
    }

    /**
     * The view of {@code stored}: an edge without spans is kept as it is, one span retags it, several cut it.
     *
     * @throws IllegalStateException when a stored node or edge id reaches {@link #FIRST_SYNTHETIC_ID}
     */
    public static RoadNetworkSnapshot build(RoadNetworkSnapshot stored, Map<Integer, List<Span>> spansByEdge) {
        Objects.requireNonNull(spansByEdge, "spansByEdge");
        for (RoadNode node : stored.nodes()) {
            if (node.id() >= FIRST_SYNTHETIC_ID) {
                throw new IllegalStateException("stored road node id " + node.id() + " collides with the routing view's ids");
            }
        }
        for (RoadEdge edge : stored.edges()) {
            if (edge.id() >= FIRST_SYNTHETIC_ID) {
                throw new IllegalStateException("stored road edge id " + edge.id() + " collides with the routing view's ids");
            }
        }
        RoadNetworkSnapshot.Builder b = stored.copyWithoutEdges();
        int nextNode = FIRST_SYNTHETIC_ID;
        int nextEdge = FIRST_SYNTHETIC_ID;
        for (RoadEdge edge : stored.edges()) {
            List<Span> spans = spansByEdge.get(edge.id());
            if (spans == null || spans.isEmpty()) {
                b.addEdge(edge);
                continue;
            }
            if (spans.size() == 1) {
                b.addEdge(copy(edge, edge.id(), edge.fromNodeId(), edge.toNodeId(), edge.geometry(), edge.length(),
                    spans.get(0)));
                continue;
            }
            EdgePolyline polyline = stored.polyline(edge);
            double length = polyline.length();
            int component = stored.componentOf(edge);
            int from = edge.fromNodeId();
            int[] fromPoint = edge.geometry().get(0);
            for (int i = 0; i < spans.size(); i++) {
                Span span = spans.get(i);
                boolean lastSpan = i == spans.size() - 1;
                int to;
                int[] toPoint;
                if (lastSpan) {
                    to = edge.toNodeId();
                    toPoint = edge.geometry().get(edge.geometry().size() - 1);
                } else {
                    toPoint = block(polyline.pointAt(span.to()));
                    to = nextNode++;
                    b.addNode(new RoadNode(to, toPoint[0], toPoint[1], toPoint[2], RoadNodeKind.SPLIT, null, component));
                }
                int id = nextEdge++;
                double share = length > 0 ? (span.to() - span.from()) / length : 0;
                b.addEdge(copy(edge, id, from, to, geometry(polyline, span, fromPoint, toPoint), edge.length() * share, span));
                b.addPiece(id, new RoadNetworkSnapshot.EdgePiece(edge.id(), span.from(), span.to()));
                from = to;
                fromPoint = toPoint;
            }
        }
        return b.build();
    }

    /** The piece's floor blocks: its start, the stored vertices strictly inside it, its end (no repeats, at least two). */
    private static List<int[]> geometry(EdgePolyline polyline, Span span, int[] start, int[] end) {
        List<int[]> out = new ArrayList<>();
        out.add(start);
        List<int[]> points = polyline.points();
        for (int i = 1; i < points.size() - 1; i++) {
            double at = polyline.cumulativeAt(i);
            if (at > span.from() && at < span.to()) {
                addPoint(out, points.get(i));
            }
        }
        addPoint(out, end);
        if (out.size() < 2) {
            out.add(end);
        }
        return out;
    }

    private static void addPoint(List<int[]> out, int[] p) {
        int[] last = out.get(out.size() - 1);
        if (last[0] != p[0] || last[1] != p[1] || last[2] != p[2]) {
            out.add(p);
        }
    }

    private static int[] block(double[] p) {
        return new int[] {(int) Math.round(p[0]), (int) Math.round(p[1]), (int) Math.round(p[2])};
    }

    private static RoadEdge copy(RoadEdge edge, int id, int from, int to, List<int[]> geometry, double length, Span span) {
        return new RoadEdge(id, from, to, geometry, length, edge.avgWidth(), edge.profileId(), edge.streetId(),
            edge.costMultiplier(), edge.flags(), span.gateDoorIds(), edge.domainIds(), span.regionIds(), edge.source(),
            edge.stale(), edge.confirmed(), span.lanes());
    }
}

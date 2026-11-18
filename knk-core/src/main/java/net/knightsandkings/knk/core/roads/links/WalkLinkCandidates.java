package net.knightsandkings.knk.core.roads.links;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;

import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;
import net.knightsandkings.knk.core.roads.route.WalkLinkSource;

/**
 * Which pairs of road nodes may get a walk link (off-road navigation DESIGN § 5.2.1, decision O28): two nodes of the
 * same network component within {@code walk-link-range} blocks of each other (straight, 3D, floor to floor) whose road
 * distance is more than {@code walk-link-ratio} times the straight distance, or who are not joined by road within
 * {@code ratio × range} at all. Computed from the snapshot alone - no terrain, no access - once per node and cached for
 * the snapshot's life (a new snapshot gets a new instance).
 *
 * <p>Only nodes a route can turn at take part: junctions, end points, anchors and named nodes. The routing view's
 * {@link RoadNodeKind#SPLIT split} nodes (a gate door's ends, a region border) and {@link RoadNodeKind#BOUNDARY tile
 * boundaries} are plumbing, as for the maneuvers; pruned nodes are not routed on. Road distance is the walked length
 * ({@link RoadEdge#length()}), one-way edges From → To only, as the router walks them.
 *
 * <p>Thread-safe: the per-node lists are computed on demand from any routing thread.
 */
public final class WalkLinkCandidates {

    private final RoadNetworkSnapshot snapshot;
    private final double range;
    private final double ratio;
    private final Map<Long, List<Integer>> grid = new HashMap<>();
    private final Map<Integer, List<WalkLinkSource.Candidate>> byNode = new ConcurrentHashMap<>();

    /**
     * @param range {@code walk-link-range}: the longest straight distance of a link (blocks, positive)
     * @param ratio {@code walk-link-ratio}: the road must be longer than this times the straight distance (above 1)
     */
    public WalkLinkCandidates(RoadNetworkSnapshot snapshot, double range, double ratio) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        if (!(range > 0) || !(ratio > 1)) {
            throw new IllegalArgumentException("walk-link-range must be positive and walk-link-ratio above 1 (got: "
                + range + ", " + ratio + ")");
        }
        this.range = range;
        this.ratio = ratio;
        for (RoadNode node : snapshot.nodes()) {
            if (takesPart(node)) {
                grid.computeIfAbsent(cell(node.x(), node.z()), k -> new ArrayList<>()).add(node.id());
            }
        }
    }

    public RoadNetworkSnapshot snapshot() {
        return snapshot;
    }

    public double range() {
        return range;
    }

    public double ratio() {
        return ratio;
    }

    /** Whether a node can be the end of a link. */
    static boolean takesPart(RoadNode node) {
        return switch (node.kind()) {
            case SPLIT, BOUNDARY, PRUNED, PRUNED_EDGE -> false;
            default -> true;
        };
    }

    /** The candidates from {@code nodeId} (it is their From node), nearest first; empty for a node that takes no part. */
    public List<WalkLinkSource.Candidate> from(int nodeId) {
        return byNode.computeIfAbsent(nodeId, this::compute);
    }

    private List<WalkLinkSource.Candidate> compute(int nodeId) {
        RoadNode from = snapshot.node(nodeId).orElse(null);
        if (from == null || !takesPart(from)) {
            return List.of();
        }
        Map<Integer, Double> road = roadDistances(nodeId, ratio * range);
        List<WalkLinkSource.Candidate> out = new ArrayList<>();
        int cx = Math.floorDiv(from.x(), (int) Math.ceil(range));
        int cz = Math.floorDiv(from.z(), (int) Math.ceil(range));
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int otherId : grid.getOrDefault(key(cx + dx, cz + dz), List.of())) {
                    if (otherId == nodeId) {
                        continue;
                    }
                    RoadNode other = snapshot.requireNode(otherId);
                    if (other.componentId() != from.componentId()) {
                        continue;
                    }
                    double straight = distance(from, other);
                    if (straight > range || straight < 1) {
                        continue;
                    }
                    Double byRoad = road.get(otherId);
                    if (byRoad == null || byRoad > ratio * straight) {
                        out.add(new WalkLinkSource.Candidate(nodeId, otherId, straight));
                    }
                }
            }
        }
        out.sort(Comparator.comparingDouble(WalkLinkSource.Candidate::straight)
            .thenComparingInt(WalkLinkSource.Candidate::to));
        return List.copyOf(out);
    }

    /** Dijkstra over walked lengths from a node, up to {@code bound} blocks (one-way edges From → To only). */
    Map<Integer, Double> roadDistances(int start, double bound) {
        Map<Integer, Double> dist = new HashMap<>();
        PriorityQueue<double[]> open = new PriorityQueue<>(Comparator.comparingDouble(a -> a[0]));
        dist.put(start, 0.0);
        open.add(new double[] {0, start});
        while (!open.isEmpty()) {
            double[] e = open.poll();
            int node = (int) e[1];
            if (e[0] > dist.getOrDefault(node, Double.POSITIVE_INFINITY)) {
                continue;
            }
            for (int ei : snapshot.incidentEdgeIndexes(node)) {
                RoadEdge edge = snapshot.edgeAt(ei);
                boolean forward = edge.fromNodeId() == node;
                if (!forward && edge.isOneway()) {
                    continue;
                }
                int other = edge.otherNode(node);
                double d = e[0] + edge.length();
                if (d > bound || d >= dist.getOrDefault(other, Double.POSITIVE_INFINITY)) {
                    continue;
                }
                dist.put(other, d);
                open.add(new double[] {d, other});
            }
        }
        return dist;
    }

    private long cell(int x, int z) {
        int size = (int) Math.ceil(range);
        return key(Math.floorDiv(x, size), Math.floorDiv(z, size));
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xffffffffL);
    }

    private static double distance(RoadNode a, RoadNode b) {
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}

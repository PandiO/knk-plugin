package net.knightsandkings.knk.core.roads.build;

import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Stable ids across rebuilds (DESIGN §5.7): new nodes are matched to the previous build's nodes of
 * the same tile within {@link BuildParameters#nodeMatchDistance()} blocks (3D, greedy nearest first);
 * a matched node reports the old id as {@code existingId} and a {@code Locked} node keeps its old
 * position. An edge keeps its id when both its nodes matched the nodes of a previous edge and its
 * polyline stays within {@link BuildParameters#edgeMatchDistance()} of the old one. Anchor nodes are
 * matched by their anchor id, never by position. A locked node still unmatched after that takes the
 * nearest remaining candidate within {@link BuildParameters#lockedNodeReach()}. Tombstones (Pruned,
 * PrunedEdge) are never matched (the builder leaves their arm or chain out instead, see {@link SkeletonGraph}).
 */
public final class NodeMatcher {

    /** A node of the previous build (from the tile's graph download). */
    public record PreviousNode(int id, int x, int y, int z, RoadNodeKind kind, boolean locked) {
    }

    /** An edge of the previous build; {@code geometry} is the stored RDP polyline. */
    public record PreviousEdge(int id, int fromNodeId, int toNodeId, List<int[]> geometry) {
        public PreviousEdge {
            Objects.requireNonNull(geometry, "geometry");
            geometry = List.copyOf(geometry);
        }
    }

    /** The previous build of a tile (its own nodes and the edges it owns; stitch edges are harmless). */
    public record PreviousGraph(List<PreviousNode> nodes, List<PreviousEdge> edges) {
        public static final PreviousGraph EMPTY = new PreviousGraph(List.of(), List.of());

        public PreviousGraph {
            nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
            edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
        }

        public PreviousNode node(int id) {
            for (PreviousNode node : nodes) {
                if (node.id() == id) {
                    return node;
                }
            }
            return null;
        }
    }

    /** A new node's position and kind (null = unknown), for matching. */
    public record Candidate(int x, int y, int z, OptionalInt anchorId, RoadNodeKind kind) {
        public Candidate(int x, int y, int z, OptionalInt anchorId) {
            this(x, y, z, anchorId, null);
        }
    }

    /** No previous node matched. */
    public static final int UNMATCHED = -1;

    private final BuildParameters params;

    public NodeMatcher(BuildParameters params) {
        this.params = Objects.requireNonNull(params, "params");
    }

    /**
     * Previous node id per candidate (index-aligned), or {@link #UNMATCHED}. Anchors take their own
     * id; the remaining candidates are matched greedily by increasing distance to the remaining
     * non-anchor previous nodes within {@code nodeMatchDistance}; every previous node is used once.
     */
    public int[] matchNodes(List<Candidate> candidates, PreviousGraph previous) {
        int[] result = new int[candidates.size()];
        Arrays.fill(result, UNMATCHED);
        boolean[] used = new boolean[previous.nodes().size()];
        Map<Integer, Integer> previousIndexById = new HashMap<>();
        for (int j = 0; j < previous.nodes().size(); j++) {
            previousIndexById.put(previous.nodes().get(j).id(), j);
        }
        for (int i = 0; i < candidates.size(); i++) {
            OptionalInt anchorId = candidates.get(i).anchorId();
            if (anchorId.isPresent()) {
                result[i] = anchorId.getAsInt();
                Integer j = previousIndexById.get(anchorId.getAsInt());
                if (j != null) {
                    used[j] = true;
                }
            }
        }
        double max = params.nodeMatchDistance();
        List<double[]> pairs = new ArrayList<>(); // {distance, i, j}
        for (int i = 0; i < candidates.size(); i++) {
            if (result[i] != UNMATCHED) {
                continue;
            }
            Candidate c = candidates.get(i);
            for (int j = 0; j < previous.nodes().size(); j++) {
                PreviousNode p = previous.nodes().get(j);
                if (used[j] || p.kind() == RoadNodeKind.ANCHOR || p.kind().isTombstone() || !compatible(c, p)) {
                    continue;
                }
                double d = distance(c.x(), c.y(), c.z(), p.x(), p.y(), p.z());
                if (d <= max) {
                    pairs.add(new double[] {d, i, j});
                }
            }
        }
        assignNearestFirst(pairs, result, used, previous);

        // Second pass (smoke test fix plan 5.5 item 6): a locked node is admin cleanup - a merge moved
        // it, or the builder's junction re-centred (a plaza) - so it claims the nearest remaining
        // candidate within lockedNodeReach instead of a new node appearing next to it.
        double reach = params.lockedNodeReach();
        List<double[]> lockedPairs = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            if (result[i] != UNMATCHED) {
                continue;
            }
            Candidate c = candidates.get(i);
            for (int j = 0; j < previous.nodes().size(); j++) {
                PreviousNode p = previous.nodes().get(j);
                if (used[j] || !p.locked() || p.kind() == RoadNodeKind.ANCHOR || p.kind() == RoadNodeKind.BOUNDARY
                    || p.kind().isTombstone() || !compatible(c, p)) {
                    continue;
                }
                double d = distance(c.x(), c.y(), c.z(), p.x(), p.y(), p.z());
                if (d <= reach) {
                    lockedPairs.add(new double[] {d, i, j});
                }
            }
        }
        assignNearestFirst(lockedPairs, result, used, previous);
        return result;
    }

    /**
     * A Boundary candidate must stay on the tile border, but a locked node keeps its position: a
     * Boundary candidate never takes a locked non-Boundary node's id (smoke test 2026-10-02: the
     * border node of tile 2,-2 took locked junction #7 "Brink", 7 blocks inside, and the API refused
     * the tile).
     */
    private static boolean compatible(Candidate c, PreviousNode p) {
        return c.kind() != RoadNodeKind.BOUNDARY || !p.locked() || p.kind() == RoadNodeKind.BOUNDARY;
    }

    private static void assignNearestFirst(List<double[]> pairs, int[] result, boolean[] used, PreviousGraph previous) {
        pairs.sort(Comparator.<double[]>comparingDouble(p -> p[0])
            .thenComparingDouble(p -> p[1]).thenComparingDouble(p -> p[2]));
        for (double[] pair : pairs) {
            int i = (int) pair[1];
            int j = (int) pair[2];
            if (result[i] == UNMATCHED && !used[j]) {
                result[i] = previous.nodes().get(j).id();
                used[j] = true;
            }
        }
    }

    /**
     * The previous edge id for an edge between two matched nodes, when a previous edge joins the same
     * pair (either order) and the new polyline stays within {@code edgeMatchDistance} of the old one.
     */
    public OptionalInt matchEdge(int fromExistingId, int toExistingId, List<int[]> geometry, PreviousGraph previous) {
        if (fromExistingId == UNMATCHED || toExistingId == UNMATCHED) {
            return OptionalInt.empty();
        }
        for (PreviousEdge edge : previous.edges()) {
            boolean same = edge.fromNodeId() == fromExistingId && edge.toNodeId() == toExistingId;
            boolean reversed = edge.fromNodeId() == toExistingId && edge.toNodeId() == fromExistingId;
            if (!same && !reversed) {
                continue;
            }
            if (edge.geometry().size() < 2 || geometry.size() < 2
                || polylineDistance(geometry, edge.geometry()) <= params.edgeMatchDistance()) {
                return OptionalInt.of(edge.id());
            }
        }
        return OptionalInt.empty();
    }

    /** Symmetric polyline distance: the larger of the two "farthest point to the other polyline" values. */
    public static double polylineDistance(List<int[]> a, List<int[]> b) {
        return Math.max(farthest(a, b), farthest(b, a));
    }

    private static double farthest(List<int[]> points, List<int[]> polyline) {
        double worst = 0;
        for (int[] p : points) {
            double best = Double.MAX_VALUE;
            if (polyline.size() == 1) {
                int[] q = polyline.get(0);
                best = distance(p[0], p[1], p[2], q[0], q[1], q[2]);
            }
            for (int k = 1; k < polyline.size(); k++) {
                best = Math.min(best, Rdp.distanceToSegment(p, polyline.get(k - 1), polyline.get(k)));
            }
            worst = Math.max(worst, best);
        }
        return worst;
    }

    private static double distance(int ax, int ay, int az, int bx, int by, int bz) {
        double dx = ax - bx;
        double dy = ay - by;
        double dz = az - bz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}

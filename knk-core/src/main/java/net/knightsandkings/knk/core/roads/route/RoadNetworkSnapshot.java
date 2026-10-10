package net.knightsandkings.knk.core.roads.route;

import net.knightsandkings.knk.core.domain.roads.RoadClass;
import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * The immutable per-world road network the router works on (plan 2d): nodes, edges, each edge's
 * polyline decoded once ({@link EdgePolyline}), the profiles' road classes, the street names, the
 * set of every region id an edge passes, and the {@link SegmentIndex}. Built from the tile graph
 * downloads plus the network meta (Phase 1 contract); the paper-side cache swaps a new snapshot in
 * atomically, so routing can run on any thread.
 *
 * <p>Stitch edges (plan D7, Phase 1 decision 1) may reference a node of the neighbour tile by id;
 * the builder resolves node ids across every tile it was given and <b>drops</b> edges whose node
 * is not present (a neighbour tile not downloaded yet) - their ids are kept in
 * {@link #unresolvedEdgeIds()} so the cache can log them.
 */
public final class RoadNetworkSnapshot {

    /** What the router needs from a road profile (DESIGN §3.1): its class and cost tuning. */
    public record Profile(int id, String name, RoadClass roadClass, double costMultiplier) {
        public Profile {
            Objects.requireNonNull(roadClass, "roadClass");
            if (!(costMultiplier > 0)) {
                throw new IllegalArgumentException("profile " + id + ": costMultiplier must be > 0");
            }
        }
    }

    /** A street label target (the meta's {@code streets} list). */
    public record Street(int id, String name) {
    }

    /**
     * Where an edge of a routing view lies on the stored edge it was cut from (rev. 7 Part A, {@link RoutingView}).
     *
     * @param parentEdgeId the stored edge's id
     * @param fromAlong    polyline position on the stored edge where this piece starts
     * @param toAlong      ... and where it ends ({@code > fromAlong})
     */
    public record EdgePiece(int parentEdgeId, double fromAlong, double toAlong) {
    }

    private final String world;
    private final List<RoadNode> nodes;
    private final Map<Integer, RoadNode> nodeById;
    private final List<RoadEdge> edges;
    private final Map<Integer, Integer> edgeIndexById;
    private final List<EdgePolyline> polylines;
    private final Map<Integer, int[]> incident;
    private final Map<Integer, Profile> profiles;
    private final Map<Integer, Street> streets;
    private final Set<String> regionIds;
    private final List<Integer> unresolvedEdgeIds;
    private final Map<Integer, EdgePiece> pieces;
    private final SegmentIndex segmentIndex;
    private final int bucketSize;

    private RoadNetworkSnapshot(Builder b) {
        this.world = b.world;
        this.bucketSize = b.bucketSize;
        this.nodes = List.copyOf(b.nodes.values());
        this.nodeById = Map.copyOf(b.nodes);
        List<RoadEdge> kept = new ArrayList<>(b.edges.size());
        List<Integer> unresolved = new ArrayList<>();
        for (RoadEdge edge : b.edges.values()) {
            if (b.nodes.containsKey(edge.fromNodeId()) && b.nodes.containsKey(edge.toNodeId())) {
                kept.add(edge);
            } else {
                unresolved.add(edge.id());
            }
        }
        this.edges = Collections.unmodifiableList(kept);
        this.unresolvedEdgeIds = Collections.unmodifiableList(unresolved);
        Map<Integer, Integer> index = new HashMap<>(kept.size() * 2);
        List<EdgePolyline> decoded = new ArrayList<>(kept.size());
        Map<Integer, List<Integer>> incidentLists = new HashMap<>();
        Set<String> regions = new HashSet<>();
        for (int i = 0; i < kept.size(); i++) {
            RoadEdge edge = kept.get(i);
            index.put(edge.id(), i);
            decoded.add(new EdgePolyline(edge.geometry()));
            incidentLists.computeIfAbsent(edge.fromNodeId(), k -> new ArrayList<>()).add(i);
            if (edge.toNodeId() != edge.fromNodeId()) {
                incidentLists.computeIfAbsent(edge.toNodeId(), k -> new ArrayList<>()).add(i);
            }
            regions.addAll(edge.regionIds());
            edge.lanes().forEach(regions::addAll);
        }
        this.edgeIndexById = Collections.unmodifiableMap(index);
        this.polylines = Collections.unmodifiableList(decoded);
        Map<Integer, int[]> inc = new HashMap<>(incidentLists.size() * 2);
        for (Map.Entry<Integer, List<Integer>> e : incidentLists.entrySet()) {
            inc.put(e.getKey(), e.getValue().stream().mapToInt(Integer::intValue).toArray());
        }
        this.incident = Collections.unmodifiableMap(inc);
        this.profiles = Map.copyOf(b.profiles);
        this.streets = Map.copyOf(b.streets);
        this.regionIds = Collections.unmodifiableSet(regions);
        this.pieces = Map.copyOf(b.pieces);
        this.segmentIndex = new SegmentIndex(decoded, b.bucketSize);
    }

    public static Builder builder(String world) {
        return new Builder(world);
    }

    /**
     * The same network with every edge passed through {@code retag} (live world tags, finding N4 of the
     * 2026-10-08 live test): nodes, profiles, streets and the bucket size are kept. Edges whose nodes
     * were missing were dropped when this snapshot was built and stay dropped.
     */
    public RoadNetworkSnapshot retag(java.util.function.UnaryOperator<RoadEdge> retag) {
        Builder b = builder(world).bucketSize(bucketSize).addNodes(nodes);
        for (RoadEdge edge : edges) {
            b.addEdge(Objects.requireNonNull(retag.apply(edge), "retag"));
        }
        profiles.values().forEach(b::addProfile);
        streets.values().forEach(b::addStreet);
        pieces.forEach(b::addPiece);
        return b.build();
    }

    /** A builder with this network's nodes, profiles, streets, pieces and bucket size, and no edges ({@link RoutingView}). */
    Builder copyWithoutEdges() {
        Builder b = builder(world).bucketSize(bucketSize).addNodes(nodes);
        profiles.values().forEach(b::addProfile);
        streets.values().forEach(b::addStreet);
        pieces.forEach(b::addPiece);
        return b;
    }

    /** An empty network for a world (routing refuses everything). */
    public static RoadNetworkSnapshot empty(String world) {
        return builder(world).build();
    }

    public String world() {
        return world;
    }

    public int nodeCount() {
        return nodes.size();
    }

    public int edgeCount() {
        return edges.size();
    }

    public boolean isEmpty() {
        return edges.isEmpty();
    }

    /** Every node, in insertion order. */
    public List<RoadNode> nodes() {
        return nodes;
    }

    /** Every resolved edge, in index order (the order {@link SegmentIndex} and the router use). */
    public List<RoadEdge> edges() {
        return edges;
    }

    public Optional<RoadNode> node(int id) {
        return Optional.ofNullable(nodeById.get(id));
    }

    /** @throws IllegalArgumentException when the node is unknown */
    public RoadNode requireNode(int id) {
        RoadNode node = nodeById.get(id);
        if (node == null) {
            throw new IllegalArgumentException("unknown road node " + id);
        }
        return node;
    }

    public Optional<RoadEdge> edge(int id) {
        Integer index = edgeIndexById.get(id);
        return index == null ? Optional.empty() : Optional.of(edges.get(index));
    }

    /** @throws IllegalArgumentException when the edge is unknown */
    public RoadEdge requireEdge(int id) {
        Integer index = edgeIndexById.get(id);
        if (index == null) {
            throw new IllegalArgumentException("unknown road edge " + id);
        }
        return edges.get(index);
    }

    /** Index of the edge in {@link #edges()}, or {@code -1}. */
    public int edgeIndex(int edgeId) {
        Integer index = edgeIndexById.get(edgeId);
        return index == null ? -1 : index;
    }

    /** The edge at an index of {@link #edges()}. */
    public RoadEdge edgeAt(int index) {
        return edges.get(index);
    }

    /** The decoded polyline of the edge at an index of {@link #edges()}. */
    public EdgePolyline polylineAt(int index) {
        return polylines.get(index);
    }

    /** The decoded polyline of an edge. */
    public EdgePolyline polyline(int edgeId) {
        return polylines.get(requireIndex(edgeId));
    }

    public EdgePolyline polyline(RoadEdge edge) {
        return polyline(edge.id());
    }

    /** Indexes (into {@link #edges()}) of the edges touching a node; empty for an isolated node. */
    public int[] incidentEdgeIndexes(int nodeId) {
        int[] arr = incident.get(nodeId);
        return arr == null ? new int[0] : arr.clone();
    }

    /** The edges touching a node. */
    public List<RoadEdge> edgesAt(int nodeId) {
        int[] arr = incident.get(nodeId);
        if (arr == null) {
            return List.of();
        }
        List<RoadEdge> result = new ArrayList<>(arr.length);
        for (int i : arr) {
            result.add(edges.get(i));
        }
        return result;
    }

    public Optional<Profile> profile(int profileId) {
        return Optional.ofNullable(profiles.get(profileId));
    }

    public Map<Integer, Profile> profiles() {
        return profiles;
    }

    /** The road class of an edge via its profile; empty when the edge has no (known) profile. */
    public Optional<RoadClass> roadClass(RoadEdge edge) {
        if (edge.profileId().isEmpty()) {
            return Optional.empty();
        }
        Profile p = profiles.get(edge.profileId().getAsInt());
        return p == null ? Optional.empty() : Optional.of(p.roadClass());
    }

    public Optional<String> streetName(int streetId) {
        Street s = streets.get(streetId);
        return s == null ? Optional.empty() : Optional.ofNullable(s.name());
    }

    /** The street name of an edge, when labelled and known. */
    public Optional<String> streetOf(RoadEdge edge) {
        OptionalInt id = edge.streetId();
        return id.isEmpty() ? Optional.empty() : streetName(id.getAsInt());
    }

    public Map<Integer, Street> streets() {
        return streets;
    }

    /** Every WorldGuard region id some edge passes (for the paper cache's {@code warmCache}). */
    public Set<String> regionIds() {
        return regionIds;
    }

    /** Where an edge of a routing view lies on its stored edge; empty for a stored edge. */
    public Optional<EdgePiece> piece(int edgeId) {
        return Optional.ofNullable(pieces.get(edgeId));
    }

    /** The stored edge an edge was cut from, or the edge's own id (what admins and logs know). */
    public int storedEdgeId(int edgeId) {
        EdgePiece piece = pieces.get(edgeId);
        return piece == null ? edgeId : piece.parentEdgeId();
    }

    /** Every piece of a routing view, by its edge id (empty for a stored network). */
    public Map<Integer, EdgePiece> pieces() {
        return pieces;
    }

    /** Ids of the edges dropped because a node (of a tile not downloaded) was missing. */
    public List<Integer> unresolvedEdgeIds() {
        return unresolvedEdgeIds;
    }

    public SegmentIndex segmentIndex() {
        return segmentIndex;
    }

    /** The named nodes ({@code /navigate node:<name>} destinations). */
    public List<RoadNode> namedNodes() {
        List<RoadNode> named = new ArrayList<>();
        for (RoadNode n : nodes) {
            if (n.isDestination()) {
                named.add(n);
            }
        }
        return named;
    }

    /** Component id of an edge (its From node's; both ends share it). */
    public int componentOf(RoadEdge edge) {
        return requireNode(edge.fromNodeId()).componentId();
    }

    /**
     * The routing cost factor of an edge: {@code classCost(class) × profile.costMultiplier ×
     * edge.costMultiplier} (DESIGN §6.2 step 4). Edges without a profile use class factor 1.0.
     */
    public double costFactor(RoadEdge edge, Map<RoadClass, Double> classCost) {
        double factor = edge.costMultiplier();
        if (edge.profileId().isPresent()) {
            Profile p = profiles.get(edge.profileId().getAsInt());
            if (p != null) {
                factor *= p.costMultiplier();
                Double c = classCost.get(p.roadClass());
                if (c != null) {
                    factor *= c;
                }
            }
        }
        return factor;
    }

    /** {@code length × costFactor}: the cost of walking the whole edge. */
    public double edgeCost(RoadEdge edge, Map<RoadClass, Double> classCost) {
        return edge.length() * costFactor(edge, classCost);
    }

    /** The smallest cost factor of any edge (≥ 0); what keeps the A* heuristic admissible. */
    public double minCostFactor(Map<RoadClass, Double> classCost) {
        double min = Double.POSITIVE_INFINITY;
        for (RoadEdge edge : edges) {
            min = Math.min(min, costFactor(edge, classCost));
        }
        return edges.isEmpty() ? 1.0 : min;
    }

    private int requireIndex(int edgeId) {
        Integer index = edgeIndexById.get(edgeId);
        if (index == null) {
            throw new IllegalArgumentException("unknown road edge " + edgeId);
        }
        return index;
    }

    /** Collects tile graphs and meta; last write per id wins. */
    public static final class Builder {
        private final String world;
        private final Map<Integer, RoadNode> nodes = new LinkedHashMap<>();
        private final Map<Integer, RoadEdge> edges = new LinkedHashMap<>();
        private final Map<Integer, Profile> profiles = new LinkedHashMap<>();
        private final Map<Integer, Street> streets = new LinkedHashMap<>();
        private final Map<Integer, EdgePiece> pieces = new HashMap<>();
        private int bucketSize = SegmentIndex.DEFAULT_BUCKET_SIZE;

        private Builder(String world) {
            this.world = Objects.requireNonNull(world, "world");
        }

        public Builder addNode(RoadNode node) {
            nodes.put(node.id(), node);
            return this;
        }

        public Builder addNodes(Iterable<RoadNode> more) {
            for (RoadNode n : more) {
                addNode(n);
            }
            return this;
        }

        public Builder addEdge(RoadEdge edge) {
            edges.put(edge.id(), edge);
            return this;
        }

        public Builder addEdges(Iterable<RoadEdge> more) {
            for (RoadEdge e : more) {
                addEdge(e);
            }
            return this;
        }

        public Builder addProfile(Profile profile) {
            profiles.put(profile.id(), profile);
            return this;
        }

        public Builder addStreet(Street street) {
            streets.put(street.id(), street);
            return this;
        }

        /** Marks an edge as a piece of a stored edge (routing views, rev. 7 Part A). */
        public Builder addPiece(int edgeId, EdgePiece piece) {
            pieces.put(edgeId, Objects.requireNonNull(piece, "piece"));
            return this;
        }

        /** Segment index bucket side (default {@link SegmentIndex#DEFAULT_BUCKET_SIZE}). */
        public Builder bucketSize(int size) {
            this.bucketSize = size;
            return this;
        }

        public RoadNetworkSnapshot build() {
            return new RoadNetworkSnapshot(this);
        }
    }
}

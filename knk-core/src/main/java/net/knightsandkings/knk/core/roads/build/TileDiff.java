package net.knightsandkings.knk.core.roads.build;

import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeSource;
import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import net.knightsandkings.knk.core.domain.roads.RoadTileGraph;
import net.knightsandkings.knk.core.roads.build.TileProposal.End;
import net.knightsandkings.knk.core.roads.build.TileProposal.Item;
import net.knightsandkings.knk.core.roads.build.TileProposal.Kind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Rebuilds of Curated tiles become a reviewed list of changes (rev. 6 Part B, plan §5.7). Pure.
 *
 * <ul>
 *   <li>{@link #compute}: the differences between a fresh {@link TileBuildResult} (whose ids
 *       {@link NodeMatcher} already matched to the stored graph) and the stored tile graph. Never
 *       proposed: Recorded and Stitch edges, Confirmed edges, locked nodes (anchors, tombstones,
 *       admin-edited nodes).</li>
 *   <li>{@link #withoutRejected}: drops items that match an entry of the tile's rejected list.</li>
 *   <li>{@link #merge}: the upload for a review step: the <b>current</b> stored graph plus the accepted
 *       items, with the dependency rules. An item that no longer fits the current graph (an admin edited
 *       it since) is skipped with a reason, so a proposal can never undo an edit made after it.</li>
 * </ul>
 */
public final class TileDiff {
    /** A matched node closer than this to its stored position is not proposed as moved (builder jitter). */
    public static final double MOVE_TOLERANCE = 2.0;

    /** Matching tolerances (from {@link BuildParameters}). */
    public record Settings(double nodeMatchDistance, double edgeMatchDistance, double moveTolerance) {
        public static Settings of(BuildParameters params) {
            return new Settings(params.nodeMatchDistance(), params.edgeMatchDistance(), MOVE_TOLERANCE);
        }
    }

    /** {@link #withoutRejected}: the items to show and how many the rejected list hid. */
    public record Filtered(List<Item> items, int hidden) {
        public Filtered {
            items = List.copyOf(items);
        }
    }

    /**
     * {@link #merge}: the graph to upload, which items took effect ({@code applied}, including the ones
     * a dependency rule brought along), which no longer fit ({@code skipped}, number → reason).
     */
    public record Merge(TileBuildResult upload, Set<Integer> applied, Map<Integer, String> skipped) {
        public Merge {
            applied = Set.copyOf(applied);
            skipped = Map.copyOf(skipped);
        }
    }

    private final Settings settings;

    public TileDiff(Settings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    // ===== compute =====

    /** The items (numbered from 1 in a stable order: removals, additions, changes, moves). */
    public List<Item> compute(RoadTileGraph stored, TileBuildResult build) {
        Map<Integer, RoadNode> storedNodes = new HashMap<>();
        for (RoadNode node : stored.nodes()) {
            if (!node.kind().isTombstone()) {
                storedNodes.put(node.id(), node);
            }
        }
        Map<Integer, RoadEdge> storedDetected = new LinkedHashMap<>();
        Map<Long, RoadEdge> storedPairs = new HashMap<>();
        Set<Integer> recordedEnds = new HashSet<>();
        for (RoadEdge edge : stored.edges()) {
            storedPairs.put(pair(edge.fromNodeId(), edge.toNodeId()), edge);
            if (edge.source() == RoadEdgeSource.DETECTED) {
                storedDetected.put(edge.id(), edge);
            } else if (edge.source() == RoadEdgeSource.RECORDED) {
                recordedEnds.add(edge.fromNodeId());
                recordedEnds.add(edge.toNodeId());
            }
        }
        Map<String, TileBuildResult.Node> buildNodes = new HashMap<>();
        Map<Integer, TileBuildResult.Node> buildByStoredId = new HashMap<>();
        for (TileBuildResult.Node node : build.nodes()) {
            buildNodes.put(node.key(), node);
            if (node.existingId().isPresent() && storedNodes.containsKey(node.existingId().getAsInt())) {
                buildByStoredId.put(node.existingId().getAsInt(), node);
            }
        }

        List<Item> removed = new ArrayList<>();
        List<Item> added = new ArrayList<>();
        List<Item> changed = new ArrayList<>();
        List<Item> moved = new ArrayList<>();
        Set<Integer> matchedEdges = new HashSet<>();

        for (TileBuildResult.Edge edge : build.edges()) {
            End a = end(buildNodes.get(edge.fromKey()), storedNodes);
            End b = end(buildNodes.get(edge.toKey()), storedNodes);
            if (a == null || b == null) {
                continue; // a key the build did not emit as a node (never happens with TileBuilder)
            }
            RoadEdge match = null;
            if (edge.existingId().isPresent() && storedDetected.containsKey(edge.existingId().getAsInt())
                && !matchedEdges.contains(edge.existingId().getAsInt())) {
                match = storedDetected.get(edge.existingId().getAsInt());
            } else if (!a.isNew() && !b.isNew()) {
                RoadEdge samePair = storedPairs.get(pair(a.nodeId(), b.nodeId()));
                if (samePair != null) {
                    if (samePair.source() != RoadEdgeSource.DETECTED || matchedEdges.contains(samePair.id())) {
                        continue; // a recorded or stitch edge already joins them: nothing to propose
                    }
                    match = samePair;
                }
            }
            if (match == null) {
                added.add(edgeItem(Kind.EDGE_ADDED, 0, a, b, edge.geometry(), List.of(), edge, ""));
                continue;
            }
            matchedEdges.add(match.id());
            boolean forward = a.isNew() || a.nodeId() == match.fromNodeId();
            List<int[]> geometry = forward ? edge.geometry() : reversed(edge.geometry());
            End from = forward ? a : b;
            End to = forward ? b : a;
            List<String> notes = new ArrayList<>();
            // Compared with the stored course re-anchored on the build's node positions, so a moved node
            // is one NODE_MOVED item, not a change of each of its edges as well.
            double offset = NodeMatcher.polylineDistance(geometry, reanchored(match.geometry(), from, to));
            if (offset > settings.edgeMatchDistance()) {
                notes.add("course moved up to " + Math.round(offset) + " blocks");
            }
            if (!new TreeSet<>(edge.gateDoorIds()).equals(new TreeSet<>(match.gateDoorIds()))) {
                notes.add("gate doors " + new TreeSet<>(match.gateDoorIds()) + " → " + new TreeSet<>(edge.gateDoorIds()));
            }
            // WorldGuard regions, not domain ids: the domains are looked up from the regions through a cache that
            // may not know every region at build time (smoke test 2026-10-05: "domains [5, 8] → [5]" on unchanged
            // edges), and the router reads the regions anyway.
            if (!new TreeSet<>(edge.regionIds()).equals(new TreeSet<>(match.regionIds()))) {
                notes.add("regions " + new TreeSet<>(match.regionIds()) + " → " + new TreeSet<>(edge.regionIds()));
            }
            if (!edge.profileId().equals(match.profileId())) {
                notes.add("profile " + idText(match.profileId()) + " → " + idText(edge.profileId()));
            }
            if (!notes.isEmpty()) {
                changed.add(edgeItem(Kind.EDGE_CHANGED, match.id(), from, to, geometry, match.geometry(), edge, String.join(", ", notes)));
            }
        }

        for (RoadEdge edge : storedDetected.values()) {
            if (matchedEdges.contains(edge.id()) || edge.confirmed()) {
                continue;
            }
            End from = storedEnd(edge.fromNodeId(), storedNodes, buildByStoredId);
            End to = storedEnd(edge.toNodeId(), storedNodes, buildByStoredId);
            if (from == null || to == null) {
                continue; // an end in another tile: a stitch-like edge the upsert handles
            }
            removed.add(new Item(0, Kind.EDGE_REMOVED, edge.id(), from, to, edge.geometry(), List.of(), edge.length(),
                edge.avgWidth(), edge.profileId(), edge.gateDoorIds(), edge.domainIds(), edge.regionIds(), null, null, ""));
        }

        Set<Integer> confirmedEnds = new HashSet<>();
        stored.edges().stream().filter(RoadEdge::confirmed).forEach(e -> {
            confirmedEnds.add(e.fromNodeId());
            confirmedEnds.add(e.toNodeId());
        });
        for (RoadNode node : storedNodes.values()) {
            if (node.locked() || node.kind() == RoadNodeKind.ANCHOR) {
                continue;
            }
            TileBuildResult.Node built = buildByStoredId.get(node.id());
            if (built == null) {
                if (!recordedEnds.contains(node.id()) && !confirmedEnds.contains(node.id())) {
                    removed.add(nodeItem(Kind.NODE_REMOVED, new End(node.id(), node.x(), node.y(), node.z(), node.kind()), null));
                }
                continue;
            }
            int[] target = {built.x(), built.y(), built.z()};
            if (TileProposal.distance(target, new int[] {node.x(), node.y(), node.z()}) > settings.moveTolerance()) {
                moved.add(nodeItem(Kind.NODE_MOVED, new End(node.id(), node.x(), node.y(), node.z(), built.kind()), target));
            }
        }

        List<Item> all = new ArrayList<>();
        for (List<Item> group : List.of(removed, added, changed, moved)) {
            group.sort(Comparator.<Item>comparingInt(i -> i.kind().ordinal())
                .thenComparingInt(i -> i.focus()[0]).thenComparingInt(i -> i.focus()[2]).thenComparingInt(i -> i.focus()[1]));
            all.addAll(group);
        }
        return number(all);
    }

    /** Items renumbered 1..k in their order. */
    public static List<Item> number(List<Item> items) {
        List<Item> out = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            out.add(items.get(i).numbered(i + 1));
        }
        return out;
    }

    private static List<int[]> reanchored(List<int[]> geometry, End from, End to) {
        List<int[]> out = new ArrayList<>(geometry);
        out.set(0, from.position());
        out.set(out.size() - 1, to.position());
        return out;
    }

    private static End end(TileBuildResult.Node node, Map<Integer, RoadNode> storedNodes) {
        if (node == null) {
            return null;
        }
        if (node.existingId().isPresent() && storedNodes.containsKey(node.existingId().getAsInt())) {
            return new End(node.existingId().getAsInt(), node.x(), node.y(), node.z(), node.kind());
        }
        return new End(End.NEW, node.x(), node.y(), node.z(), node.kind());
    }

    private static End storedEnd(int id, Map<Integer, RoadNode> storedNodes, Map<Integer, TileBuildResult.Node> buildByStoredId) {
        RoadNode node = storedNodes.get(id);
        if (node == null) {
            return null;
        }
        TileBuildResult.Node built = buildByStoredId.get(id);
        return new End(id, node.x(), node.y(), node.z(), built != null ? built.kind() : node.kind());
    }

    private static Item edgeItem(Kind kind, int edgeId, End from, End to, List<int[]> geometry, List<int[]> before,
                                 TileBuildResult.Edge edge, String note) {
        return new Item(0, kind, edgeId, from, to, geometry, before, edge.length(), edge.avgWidth(), edge.profileId(),
            edge.gateDoorIds(), edge.domainIds(), edge.regionIds(), null, null, note);
    }

    private static Item nodeItem(Kind kind, End node, int[] target) {
        return new Item(0, kind, 0, null, null, List.of(), List.of(), 0, 0, OptionalInt.empty(), List.of(), List.of(),
            List.of(), node, target, "");
    }

    private static String idText(OptionalInt id) {
        return id.isPresent() ? "#" + id.getAsInt() : "none";
    }

    // ===== rejected list =====

    /** Drops the items an entry of the rejected list matches, and renumbers the rest. */
    public Filtered withoutRejected(List<Item> items, List<Item> rejected) {
        if (rejected.isEmpty()) {
            return new Filtered(items, 0);
        }
        List<Item> kept = new ArrayList<>();
        int hidden = 0;
        for (Item item : items) {
            if (rejected.stream().anyMatch(r -> matches(item, r))) {
                hidden++;
            } else {
                kept.add(item);
            }
        }
        return new Filtered(number(kept), hidden);
    }

    /**
     * Whether a rejected entry stands for the same proposed change: an added edge between the same ends
     * along the same course, the same change of the same edge, the same move of the same node.
     */
    public boolean matches(Item item, Item rejected) {
        if (item.kind() != rejected.kind()) {
            return false;
        }
        return switch (item.kind()) {
            case EDGE_ADDED -> ((sameEnd(item.from(), rejected.from()) && sameEnd(item.to(), rejected.to()))
                    || (sameEnd(item.from(), rejected.to()) && sameEnd(item.to(), rejected.from())))
                && NodeMatcher.polylineDistance(item.geometry(), rejected.geometry()) <= settings.edgeMatchDistance();
            case EDGE_CHANGED -> item.edgeId() == rejected.edgeId()
                && NodeMatcher.polylineDistance(item.geometry(), rejected.geometry()) <= settings.edgeMatchDistance()
                && new TreeSet<>(item.gateDoorIds()).equals(new TreeSet<>(rejected.gateDoorIds()))
                && new TreeSet<>(item.regionIds()).equals(new TreeSet<>(rejected.regionIds()))
                && item.profileId().equals(rejected.profileId());
            case NODE_MOVED -> item.node().nodeId() == rejected.node().nodeId()
                && TileProposal.distance(item.target(), rejected.target()) <= settings.moveTolerance();
            // A rejected removal confirmed the edge / locked the node, which keeps it out of proposals by itself; its
            // rejected-list entry is only the record unreject undoes, so it never hides anything.
            case EDGE_REMOVED, NODE_REMOVED -> false;
        };
    }

    private boolean sameEnd(End a, End b) {
        if (!a.isNew() && !b.isNew()) {
            return a.nodeId() == b.nodeId();
        }
        return TileProposal.distance(a.position(), b.position()) <= settings.nodeMatchDistance();
    }

    // ===== selection =====

    /**
     * The selection plus what the dependency rules bring along: accepting a removed node accepts the
     * removal of its edges.
     */
    public static Set<Integer> withDependencies(List<Item> items, Set<Integer> selected) {
        Set<Integer> out = new TreeSet<>(selected);
        for (Item item : items) {
            if (item.kind() == Kind.NODE_REMOVED && selected.contains(item.n())) {
                int node = item.node().nodeId();
                for (Item other : items) {
                    if (other.kind() == Kind.EDGE_REMOVED && (other.from().nodeId() == node || other.to().nodeId() == node)) {
                        out.add(other.n());
                    }
                }
            }
        }
        return out;
    }

    // ===== merge =====

    /** A node of the graph being merged. */
    private static final class MNode {
        final int id; // 0 = new
        final String key;
        int x;
        int y;
        int z;
        RoadNodeKind kind;
        final boolean locked;

        MNode(int id, String key, int x, int y, int z, RoadNodeKind kind, boolean locked) {
            this.id = id;
            this.key = key;
            this.x = x;
            this.y = y;
            this.z = z;
            this.kind = kind;
            this.locked = locked;
        }

        int[] position() {
            return new int[] {x, y, z};
        }
    }

    /** A detected edge of the graph being merged. */
    private static final class MEdge {
        final int id; // 0 = new
        MNode from;
        MNode to;
        List<int[]> geometry;
        double length;
        double avgWidth;
        OptionalInt profileId;
        List<Integer> gateDoorIds;
        List<Integer> domainIds;
        List<String> regionIds;

        MEdge(int id, MNode from, MNode to, List<int[]> geometry, double length, double avgWidth, OptionalInt profileId,
              List<Integer> gateDoorIds, List<Integer> domainIds, List<String> regionIds) {
            this.id = id;
            this.from = from;
            this.to = to;
            this.geometry = geometry;
            this.length = length;
            this.avgWidth = avgWidth;
            this.profileId = profileId;
            this.gateDoorIds = gateDoorIds;
            this.domainIds = domainIds;
            this.regionIds = regionIds;
        }
    }

    /**
     * The upload for accepting {@code accepted} (item numbers; dependencies are added here) on top of the
     * <b>current</b> stored graph. {@code builderVersion}, {@code cellCount}, {@code levelCount} and
     * {@code warnings} go into the upload as they are: the caller passes the proposal's figures when this
     * step finishes the review, else the tile's current ones.
     */
    public Merge merge(RoadTileGraph current, List<Item> items, Set<Integer> accepted, int builderVersion,
                       int cellCount, int levelCount, List<String> warnings) {
        Set<Integer> selected = withDependencies(items, accepted);
        Map<Integer, MNode> nodes = new LinkedHashMap<>();
        Map<String, MNode> nodeAt = new HashMap<>();
        Set<String> tombstoneAt = new HashSet<>();
        for (RoadNode node : current.nodes()) {
            if (node.kind().isTombstone()) {
                tombstoneAt.add(at(node.x(), node.y(), node.z()));
                continue;
            }
            MNode m = new MNode(node.id(), "n" + node.id(), node.x(), node.y(), node.z(), node.kind(), node.locked());
            nodes.put(node.id(), m);
            nodeAt.put(at(m.x, m.y, m.z), m);
        }
        Map<Integer, MEdge> edges = new LinkedHashMap<>();
        Set<Long> otherPairs = new HashSet<>(); // recorded and stitch edges: kept by the upsert
        Map<Integer, Integer> otherDegree = new HashMap<>();
        Map<Integer, RoadEdge> storedEdges = new HashMap<>();
        for (RoadEdge edge : current.edges()) {
            storedEdges.put(edge.id(), edge);
            MNode from = nodes.get(edge.fromNodeId());
            MNode to = nodes.get(edge.toNodeId());
            if (edge.source() != RoadEdgeSource.DETECTED || from == null || to == null) {
                otherPairs.add(pair(edge.fromNodeId(), edge.toNodeId()));
                otherDegree.merge(edge.fromNodeId(), 1, Integer::sum);
                otherDegree.merge(edge.toNodeId(), 1, Integer::sum);
                continue;
            }
            edges.put(edge.id(), new MEdge(edge.id(), from, to, edge.geometry(), edge.length(), edge.avgWidth(),
                edge.profileId(), edge.gateDoorIds(), edge.domainIds(), edge.regionIds()));
        }
        List<MEdge> newEdges = new ArrayList<>();
        List<MNode> newNodes = new ArrayList<>();
        Set<Integer> applied = new TreeSet<>();
        Map<Integer, String> skipped = new TreeMap<>();
        Set<MNode> touched = new LinkedHashSet<>();
        int[] newKeys = {0};

        List<Item> ordered = items.stream().filter(i -> selected.contains(i.n()))
            .sorted(Comparator.comparingInt((Item i) -> switch (i.kind()) {
                case EDGE_REMOVED -> 0;
                case NODE_REMOVED -> 1;
                case NODE_MOVED -> 2;
                case EDGE_CHANGED -> 3;
                case EDGE_ADDED -> 4;
            }).thenComparingInt(Item::n))
            .toList();

        for (Item item : ordered) {
            String reason = switch (item.kind()) {
                case EDGE_REMOVED -> {
                    MEdge edge = edges.get(item.edgeId());
                    if (edge == null) {
                        yield "edge #" + item.edgeId() + " is already gone";
                    }
                    RoadEdge stored = storedEdges.get(item.edgeId());
                    if (stored != null && stored.confirmed()) {
                        yield "edge #" + item.edgeId() + " was confirmed since";
                    }
                    edges.remove(item.edgeId());
                    touched.add(edge.from);
                    touched.add(edge.to);
                    kindFromEnd(edge.from, item.from());
                    kindFromEnd(edge.to, item.to());
                    yield null;
                }
                case NODE_REMOVED -> {
                    MNode node = nodes.get(item.node().nodeId());
                    if (node == null) {
                        yield "node #" + item.node().nodeId() + " is already gone";
                    }
                    if (node.locked) {
                        yield "node #" + node.id + " was locked since";
                    }
                    if (otherDegree.getOrDefault(node.id, 0) > 0) {
                        yield "node #" + node.id + " has a recorded or stitch edge";
                    }
                    for (MEdge edge : new ArrayList<>(edges.values())) {
                        if (edge.from == node || edge.to == node) {
                            edges.remove(edge.id);
                            touched.add(edge.from == node ? edge.to : edge.from);
                        }
                    }
                    newEdges.removeIf(e -> e.from == node || e.to == node);
                    nodes.remove(node.id);
                    nodeAt.remove(at(node.x, node.y, node.z));
                    yield null;
                }
                case NODE_MOVED -> {
                    MNode node = nodes.get(item.node().nodeId());
                    if (node == null) {
                        yield "node #" + item.node().nodeId() + " is gone";
                    }
                    if (node.locked) {
                        yield "node #" + node.id + " was locked since";
                    }
                    if (node.x != item.node().x() || node.y != item.node().y() || node.z != item.node().z()) {
                        yield "node #" + node.id + " was moved since";
                    }
                    int[] t = item.target();
                    MNode occupant = nodeAt.get(at(t[0], t[1], t[2]));
                    if (occupant != null || tombstoneAt.contains(at(t[0], t[1], t[2]))) {
                        yield "another node sits at the target";
                    }
                    nodeAt.remove(at(node.x, node.y, node.z));
                    node.x = t[0];
                    node.y = t[1];
                    node.z = t[2];
                    nodeAt.put(at(node.x, node.y, node.z), node);
                    kindFromEnd(node, item.node());
                    yield null;
                }
                case EDGE_CHANGED -> {
                    MEdge edge = edges.get(item.edgeId());
                    if (edge == null) {
                        yield "edge #" + item.edgeId() + " is gone";
                    }
                    if (!samePolyline(edge.geometry, item.before())) {
                        yield "edge #" + item.edgeId() + " was changed since";
                    }
                    edge.geometry = item.geometry();
                    edge.length = item.length();
                    edge.avgWidth = item.avgWidth();
                    edge.profileId = item.profileId();
                    edge.gateDoorIds = item.gateDoorIds();
                    edge.domainIds = item.domainIds();
                    edge.regionIds = item.regionIds();
                    touched.add(edge.from);
                    touched.add(edge.to);
                    kindFromEnd(edge.from, item.from());
                    kindFromEnd(edge.to, item.to());
                    yield null;
                }
                case EDGE_ADDED -> {
                    MNode from = resolve(item.from(), nodes, nodeAt, tombstoneAt, newKeys);
                    MNode to = resolve(item.to(), nodes, nodeAt, tombstoneAt, newKeys);
                    if (from == null || to == null) {
                        yield "an end node is gone";
                    }
                    if (from == to) {
                        yield "both ends are the same node now";
                    }
                    if (joined(from, to, edges.values(), newEdges, otherPairs)) {
                        yield "the two nodes are already joined";
                    }
                    for (MNode end : List.of(from, to)) {
                        if (end.id == 0 && !newNodes.contains(end)) {
                            newNodes.add(end);
                        }
                    }
                    nodeAt.put(at(from.x, from.y, from.z), from);
                    nodeAt.put(at(to.x, to.y, to.z), to);
                    newEdges.add(new MEdge(0, from, to, item.geometry(), item.length(), item.avgWidth(), item.profileId(),
                        item.gateDoorIds(), item.domainIds(), item.regionIds()));
                    touched.add(from);
                    touched.add(to);
                    kindFromEnd(from, item.from());
                    kindFromEnd(to, item.to());
                    yield null;
                }
            };
            if (reason == null) {
                applied.add(item.n());
            } else {
                skipped.put(item.n(), reason);
            }
        }

        // A detected, unlocked node an accepted item left without any edge goes too (dependency rule).
        Map<MNode, Integer> degree = new HashMap<>();
        for (MEdge edge : edges.values()) {
            degree.merge(edge.from, 1, Integer::sum);
            degree.merge(edge.to, 1, Integer::sum);
        }
        for (MEdge edge : newEdges) {
            degree.merge(edge.from, 1, Integer::sum);
            degree.merge(edge.to, 1, Integer::sum);
        }
        for (MNode node : touched) {
            if (!node.locked && node.kind != RoadNodeKind.ANCHOR && degree.getOrDefault(node, 0) == 0
                && otherDegree.getOrDefault(node.id, 0) == 0) {
                nodes.values().remove(node);
                newNodes.remove(node);
            }
        }
        // Items whose effect happened through a dependency: a removed node that is gone now.
        for (Item item : items) {
            if (item.kind() == Kind.NODE_REMOVED && !applied.contains(item.n()) && !skipped.containsKey(item.n())
                && nodes.get(item.node().nodeId()) == null) {
                applied.add(item.n());
            }
        }

        List<MNode> finalNodes = new ArrayList<>(nodes.values());
        finalNodes.addAll(newNodes);
        List<TileBuildResult.Node> uploadNodes = new ArrayList<>();
        for (MNode node : finalNodes) {
            uploadNodes.add(new TileBuildResult.Node(node.key, node.id == 0 ? OptionalInt.empty() : OptionalInt.of(node.id),
                node.x, node.y, node.z, node.kind));
        }
        List<TileBuildResult.Edge> uploadEdges = new ArrayList<>();
        Set<MNode> kept = new HashSet<>(finalNodes);
        for (MEdge edge : edges.values()) {
            if (kept.contains(edge.from) && kept.contains(edge.to)) {
                uploadEdges.add(uploadEdge(edge, OptionalInt.of(edge.id)));
            }
        }
        for (MEdge edge : newEdges) {
            if (kept.contains(edge.from) && kept.contains(edge.to)) {
                uploadEdges.add(uploadEdge(edge, OptionalInt.empty()));
            }
        }
        TileBuildResult upload = new TileBuildResult(builderVersion, cellCount, levelCount, uploadNodes, uploadEdges,
            warnings.stream().map(BuildWarning::parse).toList());
        return new Merge(upload, applied, skipped);
    }

    private static TileBuildResult.Edge uploadEdge(MEdge edge, OptionalInt existingId) {
        List<int[]> geometry = new ArrayList<>();
        for (int[] p : edge.geometry) {
            geometry.add(p.clone());
        }
        // Re-anchor the ends on the final node positions (a moved node takes its edges' ends along).
        geometry.set(0, edge.from.position());
        geometry.set(geometry.size() - 1, edge.to.position());
        double polyline = 0;
        for (int i = 1; i < geometry.size(); i++) {
            polyline += TileProposal.distance(geometry.get(i - 1), geometry.get(i));
        }
        double length = Math.max(edge.length, polyline);
        return new TileBuildResult.Edge(existingId, edge.from.key, edge.to.key, geometry, length, edge.avgWidth,
            edge.profileId, edge.gateDoorIds, edge.domainIds, edge.regionIds);
    }

    private static MNode resolve(End end, Map<Integer, MNode> nodes, Map<String, MNode> nodeAt, Set<String> tombstoneAt,
                                 int[] newKeys) {
        if (!end.isNew()) {
            return nodes.get(end.nodeId());
        }
        MNode existing = nodeAt.get(at(end.x(), end.y(), end.z()));
        if (existing != null) {
            return existing; // added before (an earlier accept, or another item of this one)
        }
        // A new node on a tombstone's block replaces the tombstone (the upsert's rule).
        return new MNode(0, "new" + (++newKeys[0]), end.x(), end.y(), end.z(), end.kind(), false);
    }

    private static boolean joined(MNode a, MNode b, Iterable<MEdge> edges, List<MEdge> newEdges, Set<Long> otherPairs) {
        if (a.id != 0 && b.id != 0 && otherPairs.contains(pair(a.id, b.id))) {
            return true;
        }
        for (MEdge edge : edges) {
            if ((edge.from == a && edge.to == b) || (edge.from == b && edge.to == a)) {
                return true;
            }
        }
        for (MEdge edge : newEdges) {
            if ((edge.from == a && edge.to == b) || (edge.from == b && edge.to == a)) {
                return true;
            }
        }
        return false;
    }

    /** The build's kind for a node an accepted item touches (only detected, unlocked nodes follow it). */
    private static void kindFromEnd(MNode node, End end) {
        if (node.locked || node.kind == RoadNodeKind.ANCHOR || end.kind().isTombstone()) {
            return;
        }
        node.kind = end.kind();
    }

    private static boolean samePolyline(List<int[]> a, List<int[]> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!java.util.Arrays.equals(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static List<int[]> reversed(List<int[]> geometry) {
        List<int[]> out = new ArrayList<>(geometry);
        java.util.Collections.reverse(out);
        return out;
    }

    private static long pair(int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        return ((long) lo << 32) | (hi & 0xffffffffL);
    }

    private static String at(int x, int y, int z) {
        return x + "," + y + "," + z;
    }
}

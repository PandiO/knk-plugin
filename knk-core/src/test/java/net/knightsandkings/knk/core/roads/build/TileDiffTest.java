package net.knightsandkings.knk.core.roads.build;

import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeSource;
import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import net.knightsandkings.knk.core.domain.roads.RoadTile;
import net.knightsandkings.knk.core.domain.roads.RoadTileGraph;
import net.knightsandkings.knk.core.roads.build.TileProposal.End;
import net.knightsandkings.knk.core.roads.build.TileProposal.Item;
import net.knightsandkings.knk.core.roads.build.TileProposal.Kind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rev. 6 Part B (plan §5.7): the proposal of a curated tile and the merge of accepted items.
 *
 * <p>Stored tile (0,0), all at y=64 on z=100 unless noted: Boundary #1 (0), Junction #2 (200), Endpoint #3
 * (300) and a spur south to Endpoint #4 (200, z=250). Detected edges #10 (1-2), #11 (2-3), #12 (2-4).
 */
class TileDiffTest {
    private static final TileDiff DIFF = new TileDiff(TileDiff.Settings.of(BuildParameters.defaults()));

    private static int[] p(int x, int z) {
        return new int[] {x, 64, z};
    }

    private static RoadNode node(int id, int x, int z, RoadNodeKind kind, boolean locked) {
        return new RoadNode(id, x, 64, z, kind, null, 1, locked);
    }

    private static RoadEdge edge(int id, int from, int to, List<int[]> geometry, RoadEdgeSource source, boolean confirmed, List<Integer> gates) {
        double length = 0;
        for (int i = 1; i < geometry.size(); i++) {
            length += TileProposal.distance(geometry.get(i - 1), geometry.get(i));
        }
        return new RoadEdge(id, from, to, geometry, length, 3, OptionalInt.of(1), OptionalInt.empty(), 1.0, Set.of(),
            gates, List.of(), List.of(), source, false, confirmed);
    }

    private static RoadEdge detected(int id, int from, int to, int[]... points) {
        return edge(id, from, to, List.of(points), RoadEdgeSource.DETECTED, false, List.of());
    }

    private static RoadTileGraph graph(List<RoadNode> nodes, List<RoadEdge> edges) {
        return new RoadTileGraph(new RoadTile(1, "world", 0, 0, 5, null, 5, false, 100, nodes.size(), edges.size(), 1, List.of()),
            nodes, edges);
    }

    private static List<RoadNode> storedNodes() {
        return List.of(node(1, 0, 100, RoadNodeKind.BOUNDARY, false), node(2, 200, 100, RoadNodeKind.JUNCTION, false),
            node(3, 300, 100, RoadNodeKind.ENDPOINT, false), node(4, 200, 250, RoadNodeKind.ENDPOINT, false));
    }

    private static List<RoadEdge> storedEdges() {
        return List.of(detected(10, 1, 2, p(0, 100), p(200, 100)), detected(11, 2, 3, p(200, 100), p(300, 100)),
            detected(12, 2, 4, p(200, 100), p(200, 250)));
    }

    private static RoadTileGraph stored() {
        return graph(storedNodes(), storedEdges());
    }

    /** A build of the same roads, ids matched (what NodeMatcher gives for an unchanged world). */
    private static final class Build {
        final List<TileBuildResult.Node> nodes = new ArrayList<>();
        final List<TileBuildResult.Edge> edges = new ArrayList<>();

        static Build same() {
            Build b = new Build();
            b.node("b", 1, 0, 100, RoadNodeKind.BOUNDARY);
            b.node("j", 2, 200, 100, RoadNodeKind.JUNCTION);
            b.node("e", 3, 300, 100, RoadNodeKind.ENDPOINT);
            b.node("s", 4, 200, 250, RoadNodeKind.ENDPOINT);
            b.edge(10, "b", "j", List.of(), p(0, 100), p(200, 100));
            b.edge(11, "j", "e", List.of(), p(200, 100), p(300, 100));
            b.edge(12, "j", "s", List.of(), p(200, 100), p(200, 250));
            return b;
        }

        Build node(String key, int existingId, int x, int z, RoadNodeKind kind) {
            nodes.removeIf(n -> n.key().equals(key));
            nodes.add(new TileBuildResult.Node(key, existingId == 0 ? OptionalInt.empty() : OptionalInt.of(existingId), x, 64, z, kind));
            return this;
        }

        Build edge(int existingId, String from, String to, List<Integer> gates, int[]... points) {
            double length = 0;
            for (int i = 1; i < points.length; i++) {
                length += TileProposal.distance(points[i - 1], points[i]);
            }
            edges.add(new TileBuildResult.Edge(existingId == 0 ? OptionalInt.empty() : OptionalInt.of(existingId), from, to,
                List.of(points), length, 3, OptionalInt.of(1), gates, List.of(), List.of()));
            return this;
        }

        Build without(String key) {
            nodes.removeIf(n -> n.key().equals(key));
            edges.removeIf(e -> e.fromKey().equals(key) || e.toKey().equals(key));
            return this;
        }

        Build withoutEdge(int existingId) {
            edges.removeIf(e -> e.existingId().equals(OptionalInt.of(existingId)));
            return this;
        }

        TileBuildResult result() {
            return new TileBuildResult(6, 900, 1, nodes, edges, List.of(new BuildWarning("Cell cap hit", 1, 64, 2)));
        }
    }

    private static List<Kind> kinds(List<Item> items) {
        return items.stream().map(Item::kind).toList();
    }

    private static TileDiff.Merge merge(RoadTileGraph current, List<Item> items, Integer... accepted) {
        return DIFF.merge(current, items, Set.of(accepted), 5, 100, 1, List.of());
    }

    private static Map<Integer, TileBuildResult.Edge> uploadedEdgesById(TileBuildResult upload) {
        return upload.edges().stream().filter(e -> e.existingId().isPresent())
            .collect(Collectors.toMap(e -> e.existingId().getAsInt(), e -> e));
    }

    private static Map<Integer, TileBuildResult.Node> uploadedNodesById(TileBuildResult upload) {
        return upload.nodes().stream().filter(n -> n.existingId().isPresent())
            .collect(Collectors.toMap(n -> n.existingId().getAsInt(), n -> n));
    }

    // ================================================================ compute

    @Test
    void anUnchangedBuildProposesNothing() {
        assertTrue(DIFF.compute(stored(), Build.same().result()).isEmpty());
    }

    @Test
    void builderJitterBelowTheTolerancesIsNoChange() {
        Build build = Build.same().node("j", 2, 201, 101, RoadNodeKind.JUNCTION);
        build.edges.clear();
        build.edge(10, "b", "j", List.of(), p(0, 100), p(100, 101), p(201, 101));
        build.edge(11, "j", "e", List.of(), p(201, 101), p(300, 100));
        build.edge(12, "j", "s", List.of(), p(201, 101), p(200, 250));

        assertTrue(DIFF.compute(stored(), build.result()).isEmpty());
    }

    @Test
    void aLostSpurProposesTheEdgeAndItsEndpoint() {
        List<Item> items = DIFF.compute(stored(), Build.same().without("s").result());

        assertEquals(List.of(Kind.EDGE_REMOVED, Kind.NODE_REMOVED), kinds(items));
        assertEquals(List.of(1, 2), items.stream().map(Item::n).toList());
        assertEquals(12, items.get(0).edgeId());
        assertEquals(4, items.get(1).node().nodeId());
        assertEquals("1 removed edge #12 (#2 → #4)", items.get(0).describe());
        assertArrayEquals(new int[] {200, 64, 175}, items.get(0).focus()); // the middle of the edge
    }

    @Test
    void aNewRoadIsOneAddedEdgeWithItsNewEnd() {
        Build build = Build.same().node("n", 0, 400, 100, RoadNodeKind.ENDPOINT).node("e", 3, 300, 100, RoadNodeKind.JUNCTION)
            .edge(0, "e", "n", List.of(), p(300, 100), p(400, 100));

        Item item = DIFF.compute(stored(), build.result()).get(0);

        assertEquals(Kind.EDGE_ADDED, item.kind());
        assertEquals(3, item.from().nodeId());
        assertEquals(RoadNodeKind.JUNCTION, item.from().kind());
        assertTrue(item.to().isNew());
        assertArrayEquals(p(400, 100), item.to().position());
    }

    @Test
    void anEdgeTracedElsewhereOrThroughAGateIsChanged() {
        Build build = Build.same();
        build.edges.clear();
        build.edge(0, "b", "j", List.of(), p(0, 100), p(100, 120), p(200, 100)); // lost its id: moved 20 blocks
        build.edge(11, "j", "e", List.of(7), p(200, 100), p(300, 100));          // now through gate 7
        build.edge(12, "s", "j", List.of(), p(200, 250), p(200, 100));           // reversed: no change

        List<Item> items = DIFF.compute(stored(), build.result());

        assertEquals(List.of(Kind.EDGE_CHANGED, Kind.EDGE_CHANGED), kinds(items));
        Item course = items.stream().filter(i -> i.edgeId() == 10).findFirst().orElseThrow();
        assertTrue(course.note().startsWith("course moved up to 20 blocks"), course.note());
        assertEquals(1, course.from().nodeId()); // oriented like the stored edge
        assertEquals(2, course.before().size());
        Item gate = items.stream().filter(i -> i.edgeId() == 11).findFirst().orElseThrow();
        assertEquals("gate doors [] → [7]", gate.note());
    }

    @Test
    void aReversedBuildEdgeIsOrientedLikeTheStoredOne() {
        Build build = Build.same();
        build.edges.removeIf(e -> e.existingId().equals(OptionalInt.of(10)));
        build.edge(0, "j", "b", List.of(), p(200, 100), p(100, 130), p(0, 100));

        Item item = DIFF.compute(stored(), build.result()).get(0);

        assertEquals(1, item.from().nodeId());
        assertArrayEquals(p(0, 100), item.geometry().get(0));
    }

    @Test
    void aNodeBeyondTheMoveToleranceIsMoved() {
        Build build = Build.same().node("e", 3, 305, 100, RoadNodeKind.ENDPOINT);
        build.edges.removeIf(e -> e.existingId().equals(OptionalInt.of(11)));
        build.edge(11, "j", "e", List.of(), p(200, 100), p(305, 100));

        List<Item> items = DIFF.compute(stored(), build.result());

        assertEquals(List.of(Kind.NODE_MOVED), kinds(items));
        assertArrayEquals(p(305, 100), items.get(0).target());
        assertEquals("1 moved node #3 by 5 blocks", items.get(0).describe());
    }

    @Test
    void lockedNodesConfirmedEdgesAndRecordedRoadsAreNeverProposed() {
        List<RoadNode> nodes = new ArrayList<>(storedNodes());
        nodes.set(3, node(4, 200, 250, RoadNodeKind.ENDPOINT, true)); // locked spur end
        nodes.add(node(5, 400, 100, RoadNodeKind.ENDPOINT, false));   // end of a recorded edge
        nodes.add(node(6, 100, 300, RoadNodeKind.PRUNED_EDGE, true)); // a tombstone
        List<RoadEdge> edges = new ArrayList<>(storedEdges());
        edges.set(2, edge(12, 2, 4, List.of(p(200, 100), p(200, 250)), RoadEdgeSource.DETECTED, true, List.of()));
        edges.add(edge(13, 3, 5, List.of(p(300, 100), p(400, 100)), RoadEdgeSource.RECORDED, false, List.of()));
        // The build lost the spur, never saw #5, and traced the recorded stretch itself.
        Build build = Build.same().without("s").node("r", 5, 400, 100, RoadNodeKind.ENDPOINT)
            .edge(0, "e", "r", List.of(), p(300, 100), p(400, 100));

        assertTrue(DIFF.compute(graph(nodes, edges), build.result()).isEmpty());
    }

    @Test
    void itemsComeInAStableOrder() {
        Build build = Build.same().without("s").node("e", 3, 306, 100, RoadNodeKind.ENDPOINT)
            .node("n", 0, 300, 400, RoadNodeKind.ENDPOINT);
        build.edges.removeIf(e -> e.existingId().equals(OptionalInt.of(11)));
        build.edge(11, "j", "e", List.of(), p(200, 100), p(306, 100));
        build.edge(0, "e", "n", List.of(), p(306, 100), p(300, 400));

        assertEquals(List.of(Kind.EDGE_REMOVED, Kind.NODE_REMOVED, Kind.EDGE_ADDED, Kind.NODE_MOVED),
            kinds(DIFF.compute(stored(), build.result())));
    }

    // ================================================================ rejected list

    @Test
    void aRejectedAdditionHidesTheSameRoadNextTime() {
        Build build = Build.same().node("n", 0, 400, 100, RoadNodeKind.ENDPOINT).edge(0, "e", "n", List.of(), p(300, 100), p(400, 100));
        Item rejected = DIFF.compute(stored(), build.result()).get(0);
        Build again = Build.same().node("n", 0, 401, 101, RoadNodeKind.ENDPOINT).edge(0, "e", "n", List.of(), p(300, 100), p(401, 101))
            .node("m", 0, 300, 400, RoadNodeKind.ENDPOINT).edge(0, "s", "m", List.of(), p(200, 250), p(300, 400));

        TileDiff.Filtered filtered = DIFF.withoutRejected(DIFF.compute(stored(), again.result()), List.of(rejected));

        assertEquals(1, filtered.hidden());
        assertEquals(1, filtered.items().size());
        assertEquals(1, filtered.items().get(0).n()); // renumbered
        assertEquals(4, filtered.items().get(0).from().nodeId());
    }

    @Test
    void aRejectedChangeStaysHiddenOnlyWhileTheChangeIsTheSame() {
        Build gate7 = Build.same();
        gate7.edges.removeIf(e -> e.existingId().equals(OptionalInt.of(11)));
        gate7.edge(11, "j", "e", List.of(7), p(200, 100), p(300, 100));
        Item rejected = DIFF.compute(stored(), gate7.result()).get(0);
        Build gate8 = Build.same();
        gate8.edges.removeIf(e -> e.existingId().equals(OptionalInt.of(11)));
        gate8.edge(11, "j", "e", List.of(8), p(200, 100), p(300, 100));

        assertEquals(1, DIFF.withoutRejected(DIFF.compute(stored(), gate7.result()), List.of(rejected)).hidden());
        assertEquals(0, DIFF.withoutRejected(DIFF.compute(stored(), gate8.result()), List.of(rejected)).hidden());
    }

    // ================================================================ merge

    @Test
    void mergingNothingUploadsTheCurrentGraph() {
        List<RoadNode> nodes = new ArrayList<>(storedNodes());
        nodes.add(node(6, 100, 300, RoadNodeKind.PRUNED, true));
        nodes.add(node(5, 400, 100, RoadNodeKind.ANCHOR, true));
        List<RoadEdge> edges = new ArrayList<>(storedEdges());
        edges.add(edge(13, 3, 5, List.of(p(300, 100), p(400, 100)), RoadEdgeSource.RECORDED, false, List.of()));
        edges.add(edge(14, 1, 99, List.of(p(0, 100), p(-1, 100)), RoadEdgeSource.STITCH, false, List.of()));

        TileDiff.Merge merge = merge(graph(nodes, edges), List.of());

        assertEquals(Set.of(1, 2, 3, 4, 5), uploadedNodesById(merge.upload()).keySet()); // no tombstone
        assertEquals(Set.of(10, 11, 12), uploadedEdgesById(merge.upload()).keySet());   // no recorded, no stitch
        assertEquals("n2", merge.upload().edges().get(0).toKey());
        assertTrue(merge.applied().isEmpty());
        assertEquals(5, merge.upload().builderVersion());
    }

    @Test
    void acceptingARemovedNodeRemovesItsEdgesAndAJunctionKeepsItsBuildKind() {
        List<Item> items = DIFF.compute(stored(), Build.same().without("s").node("j", 2, 200, 100, RoadNodeKind.JUNCTION).result());

        TileDiff.Merge merge = merge(stored(), items, 2); // the node; its edge comes along

        assertEquals(Set.of(1, 2), merge.applied());
        assertFalse(uploadedNodesById(merge.upload()).containsKey(4));
        assertFalse(uploadedEdgesById(merge.upload()).containsKey(12));
    }

    @Test
    void acceptingOnlyTheEdgeDropsTheOrphanedEndpointToo() {
        List<Item> items = DIFF.compute(stored(), Build.same().without("s").result());

        TileDiff.Merge merge = merge(stored(), items, 1);

        assertEquals(Set.of(1, 2), merge.applied()); // the node removal happened through the dependency rule
        assertFalse(uploadedNodesById(merge.upload()).containsKey(4));
    }

    @Test
    void acceptingAnAddedEdgeBringsItsNewNodeAndTheBuildKind() {
        Build build = Build.same().node("n", 0, 400, 100, RoadNodeKind.ENDPOINT).node("e", 3, 300, 100, RoadNodeKind.JUNCTION)
            .edge(0, "e", "n", List.of(), p(300, 100), p(400, 100));
        List<Item> items = DIFF.compute(stored(), build.result());

        TileBuildResult upload = merge(stored(), items, 1).upload();

        TileBuildResult.Node added = upload.nodes().stream().filter(n -> n.existingId().isEmpty()).findFirst().orElseThrow();
        assertEquals(400, added.x());
        assertEquals(RoadNodeKind.JUNCTION, uploadedNodesById(upload).get(3).kind());
        TileBuildResult.Edge edge = upload.edges().stream().filter(e -> e.existingId().isEmpty()).findFirst().orElseThrow();
        assertEquals(List.of("n3", added.key()), List.of(edge.fromKey(), edge.toKey()));
    }

    @Test
    void aSecondAcceptReusesANewNodeAnEarlierAcceptStored() {
        // Two new roads meet at a new junction (400, 100); the first accept stored it as #20.
        Build build = Build.same().node("n", 0, 400, 100, RoadNodeKind.JUNCTION).node("m", 0, 400, 300, RoadNodeKind.ENDPOINT)
            .edge(0, "e", "n", List.of(), p(300, 100), p(400, 100)).edge(0, "n", "m", List.of(), p(400, 100), p(400, 300));
        List<Item> items = DIFF.compute(stored(), build.result());
        List<RoadNode> nodes = new ArrayList<>(storedNodes());
        nodes.add(node(20, 400, 100, RoadNodeKind.JUNCTION, false));
        List<RoadEdge> edges = new ArrayList<>(storedEdges());
        edges.add(detected(21, 3, 20, p(300, 100), p(400, 100)));

        TileDiff.Merge merge = merge(graph(nodes, edges), items, 2);

        assertEquals(Set.of(2), merge.applied());
        TileBuildResult.Edge added = merge.upload().edges().stream().filter(e -> e.existingId().isEmpty()).findFirst().orElseThrow();
        assertEquals("n20", added.fromKey());
        assertEquals(1, merge.upload().nodes().stream().filter(n -> n.existingId().isEmpty()).count());
    }

    @Test
    void anItemAnAdminOverrodeSinceIsSkippedWithAReason() {
        Build build = Build.same().without("s");
        build.edges.removeIf(e -> e.existingId().equals(OptionalInt.of(10)));
        build.edge(10, "b", "j", List.of(), p(0, 100), p(100, 130), p(200, 100));
        List<Item> items = DIFF.compute(stored(), build.result()); // 1 removed #12, 2 removed #4, 3 changed #10
        // Since the proposal: #12 was confirmed (its end #4 locked) and #10 was reshaped by a node move.
        List<RoadNode> nodes = new ArrayList<>(storedNodes());
        nodes.set(3, node(4, 200, 250, RoadNodeKind.ENDPOINT, true));
        List<RoadEdge> edges = new ArrayList<>(storedEdges());
        edges.set(0, detected(10, 1, 2, p(0, 100), p(199, 100)));
        edges.set(2, edge(12, 2, 4, List.of(p(200, 100), p(200, 250)), RoadEdgeSource.DETECTED, true, List.of()));

        TileDiff.Merge merge = merge(graph(nodes, edges), items, 1, 2, 3);

        assertTrue(merge.applied().isEmpty());
        assertEquals(Map.of(1, "edge #12 was confirmed since", 2, "node #4 was locked since", 3, "edge #10 was changed since"),
            merge.skipped());
        assertEquals(2, uploadedEdgesById(merge.upload()).get(10).geometry().size()); // not the proposed course
        assertTrue(uploadedEdgesById(merge.upload()).containsKey(12));
    }

    @Test
    void aMovedNodeTakesItsEdgeEndsAlong() {
        Build build = Build.same().node("j", 2, 205, 104, RoadNodeKind.JUNCTION);
        build.edges.clear(); // a build's edges end on its nodes
        build.edge(10, "b", "j", List.of(), p(0, 100), new int[] {205, 64, 104});
        build.edge(11, "j", "e", List.of(), new int[] {205, 64, 104}, p(300, 100));
        build.edge(12, "j", "s", List.of(), new int[] {205, 64, 104}, p(200, 250));
        List<Item> items = DIFF.compute(stored(), build.result());
        assertEquals(List.of(Kind.NODE_MOVED), kinds(items));

        TileBuildResult upload = merge(stored(), items, 1).upload();

        assertArrayEquals(new int[] {205, 64, 104}, new int[] {uploadedNodesById(upload).get(2).x(), 64, uploadedNodesById(upload).get(2).z()});
        for (TileBuildResult.Edge edge : upload.edges()) {
            int[] end = edge.fromKey().equals("n2") ? edge.geometry().get(0) : edge.geometry().get(edge.geometry().size() - 1);
            assertArrayEquals(new int[] {205, 64, 104}, end);
            assertTrue(edge.length() >= edge.chord());
        }
    }

    @Test
    void acceptingEverythingGivesTheBuildsGraph() {
        Build build = Build.same().without("s").node("n", 0, 400, 100, RoadNodeKind.ENDPOINT).node("e", 3, 300, 100, RoadNodeKind.JUNCTION)
            .edge(0, "e", "n", List.of(), p(300, 100), p(400, 100));
        List<Item> items = DIFF.compute(stored(), build.result());

        TileDiff.Merge merge = DIFF.merge(stored(), items, Set.of(1, 2, 3), 6, 900, 1, List.of("Cell cap hit at (1, 64, 2)"));

        TileBuildResult upload = merge.upload();
        assertEquals(build.nodes.size(), upload.nodes().size());
        assertEquals(build.edges.size(), upload.edges().size());
        assertEquals(6, upload.builderVersion());
        assertEquals(List.of("Cell cap hit at (1, 64, 2)"), upload.warningTexts());
        assertTrue(merge.skipped().isEmpty());
    }

    @Test
    void anAddedEdgeIsSkippedWhenItsEndNodeIsGone() {
        Item item = new Item(1, Kind.EDGE_ADDED, 0, new End(4, 200, 64, 250, RoadNodeKind.ENDPOINT),
            new End(End.NEW, 300, 64, 250, RoadNodeKind.ENDPOINT), List.of(p(200, 250), p(300, 250)), List.of(), 100, 3,
            OptionalInt.empty(), List.of(), List.of(), List.of(), null, null, "");
        RoadTileGraph withoutSpur = graph(storedNodes().subList(0, 3), storedEdges().subList(0, 2));

        assertEquals(Map.of(1, "an end node is gone"), merge(withoutSpur, List.of(item), 1).skipped());
    }

    @Test
    void buildWarningsReadBackFromTheirText() {
        BuildWarning warning = new BuildWarning("Prune matched nothing (stale; unprune it) (node 12)", 1395, 45, -514);

        assertEquals(warning, BuildWarning.parse(warning.text()));
        assertEquals("street conflict: High Street vs Mill Lane", BuildWarning.parse("street conflict: High Street vs Mill Lane").text());
        assertFalse(BuildWarning.parse("no position").hasPosition());
    }
}

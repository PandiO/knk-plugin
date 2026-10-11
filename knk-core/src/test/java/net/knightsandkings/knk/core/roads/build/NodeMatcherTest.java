package net.knightsandkings.knk.core.roads.build;

import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import net.knightsandkings.knk.core.roads.build.NodeMatcher.Candidate;
import net.knightsandkings.knk.core.roads.build.NodeMatcher.PreviousEdge;
import net.knightsandkings.knk.core.roads.build.NodeMatcher.PreviousGraph;
import net.knightsandkings.knk.core.roads.build.NodeMatcher.PreviousNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeMatcherTest {
    private final NodeMatcher matcher = new NodeMatcher(BuildParameters.defaults());

    private static Candidate at(int x, int y, int z) {
        return new Candidate(x, y, z, OptionalInt.empty());
    }

    private static PreviousNode prev(int id, int x, int y, int z) {
        return new PreviousNode(id, x, y, z, RoadNodeKind.ENDPOINT, false);
    }

    private static int[] p(int x, int y, int z) {
        return new int[] {x, y, z};
    }

    @Test
    void nearestWithinThreeBlocksMatchesGreedily() {
        PreviousGraph previous = new PreviousGraph(List.of(prev(10, 0, 64, 0), prev(11, 20, 64, 0), prev(12, 40, 64, 0)), List.of());
        int[] ids = matcher.matchNodes(List.of(at(1, 64, 1), at(22, 65, 0), at(60, 64, 0), at(44, 64, 0)), previous);
        assertArrayEquals(new int[] {10, 11, NodeMatcher.UNMATCHED, NodeMatcher.UNMATCHED}, ids);
    }

    @Test
    void eachPreviousNodeIsUsedOnceClosestFirst() {
        PreviousGraph previous = new PreviousGraph(List.of(prev(1, 0, 64, 0)), List.of());
        int[] ids = matcher.matchNodes(List.of(at(2, 64, 0), at(1, 64, 0)), previous);
        assertArrayEquals(new int[] {NodeMatcher.UNMATCHED, 1}, ids);
    }

    @Test
    void distanceIsThreeDimensional() {
        PreviousGraph previous = new PreviousGraph(List.of(prev(1, 0, 64, 0)), List.of());
        assertArrayEquals(new int[] {1}, matcher.matchNodes(List.of(at(0, 67, 0)), previous));
        assertArrayEquals(new int[] {NodeMatcher.UNMATCHED}, matcher.matchNodes(List.of(at(0, 68, 0)), previous));
        assertArrayEquals(new int[] {1}, matcher.matchNodes(List.of(at(2, 66, 1)), previous), "sqrt(4+4+1) = 3 matches too");
        assertArrayEquals(new int[] {NodeMatcher.UNMATCHED}, matcher.matchNodes(List.of(at(2, 66, 2)), previous), "sqrt(12) > 3");
    }

    @Test
    void aLockedNodeClaimsTheNearestLeftoverCandidateWithinItsReach() {
        PreviousNode locked = new PreviousNode(1, 0, 64, 0, RoadNodeKind.JUNCTION, true);
        PreviousGraph previous = new PreviousGraph(List.of(locked, prev(2, 30, 64, 0)), List.of());
        int[] ids = matcher.matchNodes(List.of(at(7, 64, 0), at(5, 64, 0), at(31, 64, 0)), previous);
        assertArrayEquals(new int[] {NodeMatcher.UNMATCHED, 1, 2}, ids, "5 blocks: beyond 3, within the locked reach (8)");
        assertArrayEquals(new int[] {NodeMatcher.UNMATCHED}, matcher.matchNodes(List.of(at(9, 64, 0)), previous), "beyond 8");
        assertArrayEquals(new int[] {NodeMatcher.UNMATCHED},
            matcher.matchNodes(List.of(at(5, 64, 0)), new PreviousGraph(List.of(prev(1, 0, 64, 0)), List.of())), "unlocked: 3 only");
        PreviousNode lockedBoundary = new PreviousNode(3, 0, 64, 0, RoadNodeKind.BOUNDARY, true);
        assertArrayEquals(new int[] {NodeMatcher.UNMATCHED},
            matcher.matchNodes(List.of(at(5, 64, 0)), new PreviousGraph(List.of(lockedBoundary), List.of())), "a Boundary stays on its border");
    }

    @Test
    void aBoundaryCandidateNeverTakesALockedInnerNode() {
        Candidate border = new Candidate(1418, 48, -513, OptionalInt.empty(), RoadNodeKind.BOUNDARY);
        PreviousNode brink = new PreviousNode(7, 1418, 48, -520, RoadNodeKind.JUNCTION, true);
        assertArrayEquals(new int[] {NodeMatcher.UNMATCHED},
            matcher.matchNodes(List.of(border), new PreviousGraph(List.of(brink), List.of())), "7 blocks: the locked pass");
        PreviousNode close = new PreviousNode(7, 1418, 48, -515, RoadNodeKind.JUNCTION, true);
        assertArrayEquals(new int[] {NodeMatcher.UNMATCHED},
            matcher.matchNodes(List.of(border), new PreviousGraph(List.of(close), List.of())), "2 blocks: the normal pass");
        PreviousNode lockedBoundary = new PreviousNode(8, 1417, 48, -513, RoadNodeKind.BOUNDARY, true);
        assertArrayEquals(new int[] {8},
            matcher.matchNodes(List.of(border), new PreviousGraph(List.of(lockedBoundary), List.of())), "a locked Boundary still matches");
        assertArrayEquals(new int[] {7},
            matcher.matchNodes(List.of(new Candidate(1418, 48, -514, OptionalInt.empty(), RoadNodeKind.JUNCTION)),
                new PreviousGraph(List.of(brink), List.of())), "a junction candidate still claims it");
    }

    @Test
    void aPrunedTombstoneIsNeverMatched() {
        PreviousNode tombstone = new PreviousNode(9, 0, 64, 0, RoadNodeKind.PRUNED, true);
        assertArrayEquals(new int[] {NodeMatcher.UNMATCHED},
            matcher.matchNodes(List.of(at(0, 64, 0)), new PreviousGraph(List.of(tombstone), List.of())), "not even on its own block");
        assertEquals(RoadNodeKind.PRUNED, RoadNodeKind.fromApiName("Pruned"));
        assertEquals("Pruned", RoadNodeKind.PRUNED.apiName());
    }

    @Test
    void anEdgeTombstoneIsNeverMatchedEvenWhenLocked() {
        PreviousNode tombstone = new PreviousNode(9, 0, 64, 0, RoadNodeKind.PRUNED_EDGE, true);
        assertArrayEquals(new int[] {NodeMatcher.UNMATCHED},
            matcher.matchNodes(List.of(at(0, 64, 0)), new PreviousGraph(List.of(tombstone), List.of())), "not even on its own block");
        assertEquals("PrunedEdge", RoadNodeKind.PRUNED_EDGE.apiName());
        assertEquals(RoadNodeKind.PRUNED_EDGE, RoadNodeKind.fromApiName("prunededge"));
        assertEquals("Junction", RoadNodeKind.JUNCTION.apiName());
        assertTrue(RoadNodeKind.PRUNED_EDGE.isTombstone() && RoadNodeKind.PRUNED.isTombstone() && !RoadNodeKind.ENDPOINT.isTombstone());
    }

    @Test
    void anchorsMatchByIdAndAreNeverMatchedByPosition() {
        PreviousNode anchor = new PreviousNode(50, 5, 64, 5, RoadNodeKind.ANCHOR, true);
        PreviousGraph previous = new PreviousGraph(List.of(anchor, prev(2, 9, 64, 9)), List.of());
        int[] ids = matcher.matchNodes(List.of(new Candidate(5, 64, 5, OptionalInt.of(50)), at(6, 64, 5)), previous);
        assertEquals(50, ids[0]);
        assertEquals(NodeMatcher.UNMATCHED, ids[1], "the anchor is taken by id; the detected node next to it is new");
    }

    @Test
    void edgeKeepsItsIdWhenNodePairAndPolylineAgree() {
        PreviousEdge old = new PreviousEdge(7, 1, 2, List.of(p(0, 64, 0), p(10, 64, 0)));
        PreviousGraph previous = new PreviousGraph(List.of(prev(1, 0, 64, 0), prev(2, 10, 64, 0)), List.of(old));

        assertEquals(OptionalInt.of(7), matcher.matchEdge(1, 2, List.of(p(0, 64, 0), p(5, 64, 1), p(10, 64, 0)), previous));
        assertEquals(OptionalInt.of(7), matcher.matchEdge(2, 1, List.of(p(10, 64, 0), p(0, 64, 0)), previous), "either order");
        assertEquals(OptionalInt.empty(), matcher.matchEdge(1, 2, List.of(p(0, 64, 0), p(5, 64, 3), p(10, 64, 0)), previous), "3 blocks off");
        assertEquals(OptionalInt.empty(), matcher.matchEdge(1, 3, List.of(p(0, 64, 0), p(10, 64, 0)), previous), "other pair");
        assertEquals(OptionalInt.empty(), matcher.matchEdge(NodeMatcher.UNMATCHED, 2, List.of(p(0, 64, 0), p(10, 64, 0)), previous));
    }

    @Test
    void polylineDistanceIsSymmetricAndCatchesLongerNewLines() {
        List<int[]> a = List.of(p(0, 64, 0), p(10, 64, 0));
        List<int[]> b = List.of(p(0, 64, 0), p(10, 64, 0), p(10, 64, 5));
        assertEquals(5.0, NodeMatcher.polylineDistance(a, b), 1e-9);
        assertEquals(5.0, NodeMatcher.polylineDistance(b, a), 1e-9);
        assertEquals(0.0, NodeMatcher.polylineDistance(a, List.of(p(0, 64, 0), p(5, 64, 0), p(10, 64, 0))), 1e-9);
    }

    @Test
    void previousGraphLookups() {
        PreviousGraph previous = new PreviousGraph(List.of(prev(3, 1, 2, 3)), List.of());
        assertEquals(1, previous.node(3).x());
        assertEquals(null, previous.node(4));
        assertEquals(0, PreviousGraph.EMPTY.nodes().size());
    }
}

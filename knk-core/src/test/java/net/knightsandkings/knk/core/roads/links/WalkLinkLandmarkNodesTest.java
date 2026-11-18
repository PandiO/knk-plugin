package net.knightsandkings.knk.core.roads.links;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.roads.RoadClass;
import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeFlag;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeSource;
import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import net.knightsandkings.knk.core.landmarks.LandmarkConnections;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;
import net.knightsandkings.knk.core.roads.route.WalkLinkSource;

/**
 * Off-road phase 5.2 (PandiO/knk-workspace#709, DESIGN § 5.2.1, § 6.2.2, O05): landmarks as walk-link ends. A road
 * round a courtyard, A (0,0) - P (0,-30) - Q (20,-30) - K (20,0): 80 by road for 20 straight, but K is a tile
 * boundary, plumbing that takes no part on its own.
 */
class WalkLinkLandmarkNodesTest {

    private static final int A = 1, P = 2, Q = 3, K = 4;

    private static RoadNetworkSnapshot road() {
        RoadNetworkSnapshot.Builder b = RoadNetworkSnapshot.builder("world");
        b.addProfile(new RoadNetworkSnapshot.Profile(1, "Main", RoadClass.MAIN, 1.0));
        b.addNode(new RoadNode(A, 0, 64, 0, RoadNodeKind.JUNCTION, null, 1));
        b.addNode(new RoadNode(P, 0, 64, -30, RoadNodeKind.ANCHOR, null, 1));
        b.addNode(new RoadNode(Q, 20, 64, -30, RoadNodeKind.ANCHOR, null, 1));
        b.addNode(new RoadNode(K, 20, 64, 0, RoadNodeKind.BOUNDARY, null, 1));
        b.addEdge(edge(10, A, P, 0, 0, 0, -30));
        b.addEdge(edge(11, P, Q, 0, -30, 20, -30));
        b.addEdge(edge(12, Q, K, 20, -30, 20, 0));
        return b.build();
    }

    private static RoadEdge edge(int id, int from, int to, int x0, int z0, int x1, int z1) {
        return new RoadEdge(id, from, to, List.of(new int[] {x0, 64, z0}, new int[] {x1, 64, z1}),
            Math.hypot(x1 - x0, z1 - z0), 3, OptionalInt.of(1), OptionalInt.empty(), 1.0,
            EnumSet.noneOf(RoadEdgeFlag.class), List.of(), List.of(), List.of(), RoadEdgeSource.DETECTED, false);
    }

    private static List<Integer> to(List<WalkLinkSource.Candidate> candidates) {
        return candidates.stream().map(WalkLinkSource.Candidate::to).toList();
    }

    @Test
    void withoutLandmarksTheBoundaryTakesNoPartAsBefore() {
        RoadNetworkSnapshot road = road();
        WalkLinkCandidates before = new WalkLinkCandidates(road, 40, 2.0);
        WalkLinkCandidates empty = new WalkLinkCandidates(road, 40, 2.0, Map.of(), LandmarkConnections.INFERRED);

        assertTrue(before.from(A).isEmpty());
        assertTrue(before.from(K).isEmpty());
        for (int node : List.of(A, P, Q, K)) {
            assertEquals(before.from(node), empty.from(node), "no landmark nodes: the same candidates");
        }
    }

    @Test
    void aLandmarkOnTheBoundaryMakesItALinkEndWithInferredConnections() {
        WalkLinkCandidates candidates = new WalkLinkCandidates(road(), 40, 2.0, Map.of(K, 7),
            LandmarkConnections.INFERRED);

        assertEquals(List.of(K), to(candidates.from(A)), "A -> K: 80 by road for 20 straight");
        assertEquals(List.of(A), to(candidates.from(K)), "and back");
        assertEquals(20, candidates.from(A).get(0).straight(), 1e-9);
        assertEquals(Map.of(K, 7), candidates.landmarkNodes());
    }

    @Test
    void aBlockedStoredLinkRemovesThePair() {
        LandmarkConnections.Link blocked = new LandmarkConnections.Link(1, 2, 1.0, false, true);
        LandmarkConnections stored = id -> id == 1 || id == 2 ? List.of(blocked) : List.of();
        WalkLinkCandidates candidates = new WalkLinkCandidates(road(), 40, 2.0, Map.of(A, 1, K, 2), stored);

        assertTrue(candidates.from(A).isEmpty(), "a blocked two-way link: no candidate either way");
        assertTrue(candidates.from(K).isEmpty());

        LandmarkConnections.Link oneWay = new LandmarkConnections.Link(1, 2, 1.0, true, true);
        WalkLinkCandidates oneWayCandidates = new WalkLinkCandidates(road(), 40, 2.0, Map.of(A, 1, K, 2),
            id -> id == 1 ? List.of(oneWay) : List.of());
        assertTrue(oneWayCandidates.from(A).isEmpty(), "blocked from 1 to 2");
        assertEquals(List.of(A), to(oneWayCandidates.from(K)), "2 to 1 is not blocked");
    }
}

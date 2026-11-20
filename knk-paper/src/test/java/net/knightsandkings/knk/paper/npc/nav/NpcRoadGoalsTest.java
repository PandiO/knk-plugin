package net.knightsandkings.knk.paper.npc.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeFlag;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeSource;
import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.npc.nav.RoadGoals;
import net.knightsandkings.knk.core.roads.route.RoadGoalChoice;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;
import net.knightsandkings.knk.core.roads.route.Snapper;

/**
 * Link G1 (PandiO/knk-workspace#814): the NPC's road goal candidates are the off-road chain's
 * ({@link RoadGoalChoice#candidatesAlongRoad}), with the end of the NPC's road route as today's goal.
 */
class NpcRoadGoalsTest {

    /** As {@code RoadGoalChoiceTest}: a lower road (y 50), an upper road (y 80) and a far road, two meeting at node 2. */
    private static RoadNetworkSnapshot network() {
        return RoadNetworkSnapshot.builder("w")
            .addNode(new RoadNode(1, -20, 50, 0, RoadNodeKind.ENDPOINT, null, 1))
            .addNode(new RoadNode(2, 20, 50, 0, RoadNodeKind.JUNCTION, null, 1))
            .addNode(new RoadNode(3, 20, 80, -12, RoadNodeKind.JUNCTION, null, 1))
            .addNode(new RoadNode(4, -20, 80, -12, RoadNodeKind.ENDPOINT, null, 1))
            .addNode(new RoadNode(5, 60, 70, -40, RoadNodeKind.ENDPOINT, null, 1))
            .addNode(new RoadNode(6, 60, 70, 40, RoadNodeKind.ENDPOINT, null, 1))
            .addEdge(edge(10, 1, 2, new int[] {-20, 50, 0}, new int[] {20, 50, 0}))
            .addEdge(edge(11, 2, 3, new int[] {20, 50, 0}, new int[] {20, 80, -12}))
            .addEdge(edge(12, 3, 4, new int[] {20, 80, -12}, new int[] {-20, 80, -12}))
            .addEdge(edge(13, 5, 6, new int[] {60, 70, -40}, new int[] {60, 70, 40}))
            .build();
    }

    private static RoadEdge edge(int id, int from, int to, int[] a, int[] b) {
        return new RoadEdge(id, from, to, List.of(a, b), Math.sqrt(Math.pow(a[0] - b[0], 2) + Math.pow(a[1] - b[1], 2)
            + Math.pow(a[2] - b[2], 2)), 3, OptionalInt.empty(), OptionalInt.empty(), 1.0,
            EnumSet.noneOf(RoadEdgeFlag.class), List.of(), List.of(), List.of(), RoadEdgeSource.DETECTED, false);
    }

    @Test
    void theCandidatesAreTheOffRoadChainsWithTheRoadsEndAsTodaysGoal() {
        RoadNetworkSnapshot net = network();
        NpcRoadGoals goals = new NpcRoadGoals(w -> w.equals("w") ? net : null, () -> 48);
        // the NPC's road route ends on the upper road above the destination (0 70 0)
        NavPoint roadEnd = new NavPoint("w", 0, 80, -12);
        NavPoint goal = new NavPoint("w", 0, 70, 0);

        List<RoadGoals.Candidate> candidates = goals.candidates(roadEnd, goal, 12);

        var expected = RoadGoalChoice.candidatesAlongRoad(net, 0, 70, 0, 48, 12,
            Snapper.snapRanked(net, 0, 80, -12, 2, 1, null).orElseThrow());
        assertEquals(expected.size(), candidates.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i).edgeId(), candidates.get(i).snap().edgeId());
            assertEquals(expected.get(i).along(), candidates.get(i).snap().along(), 1e-9);
            assertEquals(new NavPoint("w", expected.get(i).x(), expected.get(i).y(), expected.get(i).z()),
                candidates.get(i).floor());
        }
        assertEquals(12, candidates.get(0).snap().edgeId(), "today's goal first");
        assertEquals(new NavPoint("w", 0, 80, -12), candidates.get(0).floor());
        assertTrue(candidates.stream().anyMatch(c -> c.snap().edgeId() == 10), "the lower road under the destination");
        assertTrue(candidates.stream().noneMatch(c -> c.snap().edgeId() == 13), "the far road is out of reach");
    }

    @Test
    void theCandidatesAreBoundedAndEmptyWithoutANetworkOrOffIt() {
        RoadNetworkSnapshot net = network();
        NpcRoadGoals goals = new NpcRoadGoals(w -> w.equals("w") ? net : null, () -> 48);
        NavPoint roadEnd = new NavPoint("w", 0, 80, -12);
        NavPoint goal = new NavPoint("w", 0, 70, 0);

        assertEquals(2, goals.candidates(roadEnd, goal, 2).size(), "at most max");
        assertTrue(goals.candidates(new NavPoint("x", 0, 80, -12), new NavPoint("x", 0, 70, 0), 12).isEmpty(),
            "no network in that world");
        assertTrue(goals.candidates(new NavPoint("w", 0, 60, -40), goal, 12).isEmpty(), "a road end off the network");
        assertTrue(goals.candidates(roadEnd, new NavPoint("x", 0, 70, 0), 12).isEmpty(), "another world");
        assertTrue(new NpcRoadGoals(w -> RoadNetworkSnapshot.builder(w).build(), () -> 48)
            .candidates(roadEnd, goal, 12).isEmpty(), "an empty network");
    }

    @Test
    void theReachIsReadWhenAsked() {
        RoadNetworkSnapshot net = network();
        double[] reach = {48};
        NpcRoadGoals goals = new NpcRoadGoals(w -> net, () -> reach[0]);
        NavPoint roadEnd = new NavPoint("w", 0, 80, -12);
        NavPoint goal = new NavPoint("w", 0, 70, 0);
        int wide = goals.candidates(roadEnd, goal, 12).size();
        reach[0] = 16;
        assertTrue(goals.candidates(roadEnd, goal, 12).size() < wide, "a shorter reach, fewer candidates");
    }
}

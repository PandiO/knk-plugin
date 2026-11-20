package net.knightsandkings.knk.core.roads.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

/** Fix link F2 (PandiO/knk-workspace#780): the candidate set and the least road-plus-walk choice. */
class RoadGoalChoiceTest {

    /**
     * A lower road (y 50) passing right under the destination (x 0, y 70, z 0), an upper road (y 80) 12 blocks north of
     * it, and a far road 60 blocks east. The two near roads meet at node 2.
     */
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
        return new RoadEdge(id, from, to, List.of(a, b), EdgePolyline.distance(new double[] {a[0], a[1], a[2]},
            new double[] {b[0], b[1], b[2]}), 3, OptionalInt.empty(), OptionalInt.empty(), 1.0,
            EnumSet.noneOf(RoadEdgeFlag.class), List.of(), List.of(), List.of(), RoadEdgeSource.DETECTED, false);
    }

    @Test
    void candidatesAreTodaysGoalFirstThenOnePerEdgeNearestFirstWithinReach() {
        RoadNetworkSnapshot net = network();
        SnapPoint today = Snapper.snap(net, 0, 70, 0, 256, 1).orElseThrow();
        assertEquals(12, today.edgeId(), "the upper road is 15.6 away, the lower one 20 below");

        List<SnapPoint> candidates = RoadGoalChoice.candidates(net, 0, 70, 0, 48, 12, today);

        assertEquals(List.of(12, 10, 11), candidates.stream().map(SnapPoint::edgeId).toList(),
            "today's first, then the lower road (20) and the ramp; the far road (60) is out of reach");
        assertEquals(20, candidates.get(1).distance(), 1e-9);
        for (int i = 2; i < candidates.size(); i++) {
            assertTrue(candidates.get(i).distance() >= candidates.get(i - 1).distance());
        }
    }

    @Test
    void candidatesAreBoundedAndSkipTheSamePlace() {
        RoadNetworkSnapshot net = network();
        // beyond node 2 the lower road's and the ramp's nearest points are both node 2: one candidate for both
        List<SnapPoint> pastTheJunction = RoadGoalChoice.candidates(net, 25, 50, 0, 48, 12, null);
        assertEquals(3, pastTheJunction.size(), "node 2 once, the upper road, the far road: " + pastTheJunction);
        assertEquals(5, pastTheJunction.get(0).distance(), 1e-9);
        assertTrue(pastTheJunction.get(1).distance() > 30);

        assertEquals(1, RoadGoalChoice.candidates(net, 0, 70, 0, 48, 1,
            Snapper.snap(net, 0, 70, 0, 256, 1).orElseThrow()).size(), "max 1: today's goal alone");
        assertThrows(IllegalArgumentException.class, () -> RoadGoalChoice.candidates(net, 0, 70, 0, 48, 0, null));
        assertTrue(RoadGoalChoice.candidates(RoadNetworkSnapshot.builder("w").build(), 0, 0, 0, 48, 4, null).isEmpty());
    }

    @Test
    void theLeastRoadPlusWalkWinsAndATieGoesToTheEarlierOne() {
        SnapPoint a = new SnapPoint(1, 0, 0, new double[] {0, 64, 0}, 3, 0);
        SnapPoint b = new SnapPoint(2, 0, 0, new double[] {9, 64, 0}, 5, 0);
        SnapPoint c = new SnapPoint(3, 0, 0, new double[] {19, 64, 0}, 8, 0);

        assertEquals(b, RoadGoalChoice.best(List.of(new RoadGoalChoice.Option(a, 100, 290),
            new RoadGoalChoice.Option(b, 120, 88), new RoadGoalChoice.Option(c, 110, 105))).orElseThrow().goal(),
            "the dead end's long walk loses to a longer road with a short walk");
        assertEquals(a, RoadGoalChoice.best(List.of(new RoadGoalChoice.Option(a, 100, 50),
            new RoadGoalChoice.Option(b, 90, 60))).orElseThrow().goal(), "a tie: the nearer road point");
        assertTrue(RoadGoalChoice.best(List.of()).isEmpty());
        assertEquals(208, new RoadGoalChoice.Option(b, 120, 88).total(), 1e-9);
    }

    @Test
    void theSameGoalIsTheSameEdgeWithinABlockAlongIt() {
        SnapPoint a = new SnapPoint(1, 0, 0.5, new double[] {0, 64, 0}, 3, 10.2);
        assertTrue(RoadGoalChoice.sameGoal(a, new SnapPoint(1, 0, 0.6, new double[] {0, 64, 0}, 3, 10.9)));
        assertFalse(RoadGoalChoice.sameGoal(a, new SnapPoint(1, 0, 0.9, new double[] {0, 64, 0}, 3, 12)));
        assertFalse(RoadGoalChoice.sameGoal(a, new SnapPoint(2, 0, 0.5, new double[] {0, 64, 0}, 3, 10.2)));
    }

    // ===== F3 (smoke test of 2026-11-19): the road onward and the walk weighed above the road =====

    @Test
    void candidatesAlongRoadAddTheNodesOnwardFromTodaysGoalWithinReach() {
        RoadNetworkSnapshot net = network();
        SnapPoint today = Snapper.snap(net, 0, 70, 0, 256, 1).orElseThrow(); // on the upper road (edge 12)

        List<SnapPoint> along = RoadGoalChoice.candidatesAlongRoad(net, 0, 70, 0, 48, 12, today);

        assertEquals(today, along.get(0), "today's goal first");
        List<double[]> points = along.stream().map(SnapPoint::point).toList();
        assertTrue(points.stream().anyMatch(p -> p[0] == 20 && p[1] == 80 && p[2] == -12), "node 3, onward: " + along);
        assertTrue(points.stream().anyMatch(p -> p[0] == -20 && p[1] == 80 && p[2] == -12), "node 4, onward: " + along);
        assertTrue(points.stream().anyMatch(p -> p[0] == 20 && p[1] == 50 && p[2] == 0), "node 2, past node 3: " + along);
        assertTrue(points.stream().noneMatch(p -> p[0] == 60), "the far road is out of reach: " + along);
        for (int i = 2; i < along.size(); i++) {
            assertTrue(along.get(i).distance() >= along.get(i - 1).distance() - 1e-9, "nearest first: " + along);
        }
        for (int i = 0; i < along.size(); i++) {
            for (int j = i + 1; j < along.size(); j++) {
                assertTrue(along.get(i).distanceTo(along.get(j)) >= RoadGoalChoice.SAME_PLACE, "no place twice: " + along);
            }
        }
        for (SnapPoint p : RoadGoalChoice.candidates(net, 0, 70, 0, 48, 12, today)) {
            assertTrue(along.stream().anyMatch(q -> RoadGoalChoice.sameGoal(p, q)), "the per-edge point stays: " + p);
        }
    }

    @Test
    void candidatesAlongRoadAreBoundedAndWithoutTodaysGoalArePerEdge() {
        RoadNetworkSnapshot net = network();
        SnapPoint today = Snapper.snap(net, 0, 70, 0, 256, 1).orElseThrow();
        assertEquals(List.of(today), RoadGoalChoice.candidatesAlongRoad(net, 0, 70, 0, 48, 1, today));
        assertEquals(3, RoadGoalChoice.candidatesAlongRoad(net, 0, 70, 0, 48, 3, today).size());
        assertEquals(RoadGoalChoice.candidates(net, 0, 70, 0, 48, 12, null).toString(),
            RoadGoalChoice.candidatesAlongRoad(net, 0, 70, 0, 48, 12, null).toString());
        // a node out of reach is not followed: within 16 blocks both ends of today's edge (25 away) are out
        assertEquals(List.of(today), RoadGoalChoice.candidatesAlongRoad(net, 0, 70, 0, 16, 12, today));
    }

    @Test
    void theWalkWeighsAboveTheRoad() {
        SnapPoint gateExit = new SnapPoint(1, 0, 0, new double[] {0, 64, 0}, 44, 0);
        SnapPoint keepGate = new SnapPoint(2, 0, 0, new double[] {20, 64, 0}, 34, 0);
        // the smoke test's scene 2 (keep-tower snapshot): the garrison exit has less road plus walk, a far longer walk
        List<RoadGoalChoice.Option> options = List.of(new RoadGoalChoice.Option(gateExit, 387, 198),
            new RoadGoalChoice.Option(keepGate, 444, 161));
        assertEquals(gateExit, RoadGoalChoice.best(options).orElseThrow().goal(), "F2: road plus walk");
        assertEquals(gateExit, RoadGoalChoice.best(options, 1).orElseThrow().goal(), "weight 1 is F2's rule");
        assertEquals(keepGate, RoadGoalChoice.best(options, 4).orElseThrow().goal(), "F3: the walk weighs 4 times");
        assertEquals(444 + 4 * 161, options.get(1).score(4), 1e-9);
        assertEquals(gateExit, RoadGoalChoice.best(List.of(new RoadGoalChoice.Option(gateExit, 100, 50),
            new RoadGoalChoice.Option(keepGate, 100, 50)), 4).orElseThrow().goal(), "a tie: the earlier one");
        assertTrue(RoadGoalChoice.best(List.of(), 4).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> RoadGoalChoice.best(options, 0.5));
        assertThrows(IllegalArgumentException.class, () -> RoadGoalChoice.best(options, Double.NaN));
    }
}

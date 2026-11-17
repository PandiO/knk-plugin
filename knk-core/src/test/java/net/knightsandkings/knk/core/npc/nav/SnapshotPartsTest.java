package net.knightsandkings.knk.core.npc.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.roads.RoadClass;
import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeFlag;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeSource;
import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.navigation.shape.PlazaCrossing;
import net.knightsandkings.knk.core.npc.nav.snapshot.NavSnapshot;
import net.knightsandkings.knk.core.npc.nav.snapshot.SnapshotBox;
import net.knightsandkings.knk.core.npc.nav.snapshot.SnapshotParts;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;

/**
 * The snapshot's route, stretches and road graph (link B1, PandiO/knk-workspace#670), in this package for the test
 * world.
 */
class SnapshotPartsTest {

    private static List<NavPoint> straight(int blocks) {
        List<NavPoint> line = new ArrayList<>();
        for (int x = 0; x <= blocks; x++) {
            line.add(new NavPoint(FakeNavWorld.WORLD, x + 0.5, 64, 0.5));
        }
        return line;
    }

    @Test
    void stretchesAreHandedOutFromTheStartUntilTheLineEnds() {
        List<String> notes = new ArrayList<>();
        List<List<double[]>> stretches = SnapshotParts.stretches(straight(40), List.of(), new FakeNavWorld(), 8, 0,
            notes);
        assertTrue(stretches.size() >= 5, "40 blocks in stretches of 5 to 8: " + stretches.size());
        double[] last = stretches.get(stretches.size() - 1).get(stretches.get(stretches.size() - 1).size() - 1);
        assertEquals(40.5, last[0], 0.6);
        assertEquals(65, last[1], 1e-9); // feet waypoints
        assertTrue(notes.isEmpty(), notes.toString());
    }

    @Test
    void stretchesStopAtAnUnloadedChunkWithANote() {
        FakeNavWorld world = new FakeNavWorld();
        world.loaded = (x, z) -> x < 20;
        List<String> notes = new ArrayList<>();
        List<List<double[]>> stretches = SnapshotParts.stretches(straight(40), List.of(), world, 8, 0, notes);
        List<double[]> lastStretch = stretches.get(stretches.size() - 1);
        assertTrue(lastStretch.get(lastStretch.size() - 1)[0] < 20, "stopped before x 20");
        assertEquals(1, notes.size());
        assertTrue(notes.get(0).contains("not loaded"), notes.get(0));
    }

    @Test
    void theRouteKeepsEveryWaypointGateAndPlaza() {
        RoadPlanner.RoadPlan plan = new RoadPlanner.RoadPlan(RoadPlanner.Status.FOUND, straight(3), null, null,
            List.of(new RoadPlanner.GateCrossing(7, new NavPoint(FakeNavWorld.WORLD, 2.5, 64, 0.5))), null,
            List.of(new PlazaCrossing(new NavPoint(FakeNavWorld.WORLD, 1, 64, 0), 0)));
        NavSnapshot.RouteLine route = SnapshotParts.route("r", straight(1).get(0), straight(3).get(3), plan, List.of(),
            "none");
        assertEquals(4, route.line().size());
        assertEquals("FOUND", route.status());
        assertEquals(7, route.gates().get(0).doorId());
        assertEquals(0, route.plazas().get(0).radius());
        assertEquals(straight(3), SnapshotParts.line(route, FakeNavWorld.WORLD));
    }

    @Test
    void roadsAreTheNodesInTheBoxAndTheEdgesThroughItWhole() {
        RoadNetworkSnapshot.Builder b = RoadNetworkSnapshot.builder(FakeNavWorld.WORLD);
        b.addProfile(new RoadNetworkSnapshot.Profile(1, "Main", RoadClass.MAIN, 1.0));
        b.addNode(new RoadNode(1, 0, 64, 0, RoadNodeKind.ENDPOINT, null, 1));
        b.addNode(new RoadNode(2, 100, 64, 0, RoadNodeKind.JUNCTION, null, 1));
        b.addNode(new RoadNode(3, 500, 64, 0, RoadNodeKind.ENDPOINT, null, 1));
        b.addEdge(new RoadEdge(10, 1, 2, List.of(new int[] {0, 64, 0}, new int[] {100, 64, 0}), 100, 3,
            OptionalInt.of(1), OptionalInt.empty(), 1.0, EnumSet.noneOf(RoadEdgeFlag.class), List.of(7), List.of(42),
            List.of("district_42"), RoadEdgeSource.DETECTED, false));
        b.addEdge(new RoadEdge(11, 2, 3, List.of(new int[] {100, 64, 0}, new int[] {500, 64, 0}), 400, 3,
            OptionalInt.of(1), OptionalInt.empty(), 1.0, EnumSet.noneOf(RoadEdgeFlag.class), List.of(), List.of(),
            List.of(), RoadEdgeSource.DETECTED, false));
        SnapshotBox box = new SnapshotBox(new int[] {-10, 50, -10}, new int[] {50, 80, 10});
        NavSnapshot.Roads roads = SnapshotParts.roads(b.build(), box);
        assertEquals(List.of(10), roads.edges().stream().map(NavSnapshot.Edge::id).toList());
        assertEquals(List.of(1, 2), roads.nodes().stream().map(NavSnapshot.Node::id).toList(),
            "node 2 is outside the box but ends an edge through it");
        NavSnapshot.Edge edge = roads.edges().get(0);
        assertEquals(List.of(7), edge.gateDoorIds());
        assertEquals(List.of("district_42"), edge.regionIds());
        assertEquals("main", edge.roadClass());
        assertEquals(0, SnapshotParts.roads(null, box).edges().size());
    }
}

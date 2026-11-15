package net.knightsandkings.knk.paper.scenario.scenarios;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.bukkit.World;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.roads.route.AStarRouter;
import net.knightsandkings.knk.core.roads.route.AccessPolicy;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;
import net.knightsandkings.knk.core.roads.route.RouteRequest;
import net.knightsandkings.knk.core.roads.route.RouterParameters;
import net.knightsandkings.knk.core.roads.route.SnapPoint;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;
import net.knightsandkings.knk.paper.scenario.ScenarioServices;

/** The fake road network the NPC scenarios walk on (PandiO/knk-workspace#543). */
class ScenarioRoadNpcsTest {

    // kept in a field, never inline (knk-plugin CLAUDE.md: a Location holds its world weakly)
    private final World world = mock(World.class);

    private ScenarioScene scene() {
        when(world.getName()).thenReturn("world");
        return new ScenarioScene("npc-test", 2, world, 30128, 100, 30000,
            new ScenarioServices(null, () -> null, () -> null, () -> null, () -> null, () -> null, () -> null, () -> null));
    }

    @Test
    void theRoadIsOneEdgeOfFloorPointsInWorldCoordinates() {
        RoadNetworkSnapshot network = ScenarioRoadNpcs.network(scene(),
            new int[][] {{3, 16}, {6, 16}, {6, 27}, {25, 27}});

        assertEquals("world", network.world());
        assertEquals(2, network.nodeCount());
        assertEquals(1, network.edgeCount());
        RoadEdge edge = network.edges().get(0);
        assertEquals(4, edge.geometry().size());
        assertArrayEquals(new int[] {30131, 101, 30016}, edge.geometry().get(0), "scene (3, 16) on the floor, y 1");
        assertArrayEquals(new int[] {30153, 101, 30027}, edge.geometry().get(3));
        assertEquals(3 + 11 + 19, edge.length(), 1e-9);
    }

    @Test
    void theRouterFindsTheWholeRoadBetweenItsEnds() {
        RoadNetworkSnapshot network = ScenarioRoadNpcs.network(scene(), new int[][] {{3, 16}, {6, 16}, {6, 27}});
        RouteRequest request = RouteRequest.of(SnapPoint.atNode(network, 1), SnapPoint.atNode(network, 2),
            AccessPolicy.ALL_OPEN, RouterParameters.defaults());

        var route = new AStarRouter(network).route(request).route();

        assertEquals(14, route.length(), 1e-9);
        List<double[]> line = route.polyline();
        assertTrue(line.stream().anyMatch(p -> p[0] == 30134 && p[2] == 30016), "the line passes the corner");
    }
}

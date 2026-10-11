package net.knightsandkings.knk.core.roads.route;

import net.knightsandkings.knk.core.domain.roads.RoadClass;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static net.knightsandkings.knk.core.roads.route.NetworkFixture.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManeuverBuilderTest {

    private final RoadNetworkSnapshot town = NetworkFixture.town();
    private final AStarRouter router = new AStarRouter(town);
    private final ManeuverBuilder builder = new ManeuverBuilder(town);

    private Route route(RoadNetworkSnapshot s, int from, int to) {
        return new AStarRouter(s).route(RouteRequest.of(SnapPoint.atNode(s, from), SnapPoint.atNode(s, to),
            AccessPolicy.ALL_OPEN, RouterParameters.defaults())).route();
    }

    @Test
    void rightTurnOntoANewStreetAtAJunction() {
        RouteRequest req = new RouteRequest(SnapPoint.atNode(town, A), List.of(SnapPoint.atNode(town, C)),
            AccessPolicy.ALL_OPEN, Map.of(RoadClass.PATH, 1.5, RoadClass.MAIN, 0.9, RoadClass.ROAD, 1.0));
        Route route = router.route(req).route(); // 10 east, 11 south
        List<Maneuver> m = builder.build(route);
        assertEquals(1, m.size());
        Maneuver turn = m.get(0);
        assertEquals(Maneuver.Kind.RIGHT, turn.kind());
        assertEquals(90, turn.bearingChange(), 1e-9);
        assertEquals("Turn right onto Merchantstreet", turn.text());
        assertEquals("Merchantstreet", turn.street());
        assertEquals(100, turn.along(), 1e-9);
        assertEquals(100, turn.position()[0], 1e-9);
        assertEquals("right", turn.direction());
    }

    @Test
    void leftTurnOntoAnUnlabelledPath() {
        Route route = route(town, B, D); // via A? 10 west (90) + 13 south (115) = 205 vs 11 + 12 = 200 → via C
        assertEquals(List.of(E_BC, E_CD), AStarRouterTest.edges(route));
        // force via A with a goal on the path
        Route viaA = router.route(RouteRequest.of(SnapPoint.atNode(town, B), SnapPoint.onEdge(town, E_AD, 30),
            AccessPolicy.ALL_OPEN, RouterParameters.defaults())).route();
        assertEquals(List.of(-E_AB, E_AD), AStarRouterTest.edges(viaA));
        List<Maneuver> m = builder.build(viaA);
        assertEquals(1, m.size());
        assertEquals(Maneuver.Kind.LEFT, m.get(0).kind());
        assertEquals(-90, m.get(0).bearingChange(), 1e-9);
        assertEquals("Take the path on the left", m.get(0).text());
        assertNull(m.get(0).street());
    }

    @Test
    void straightOnWithAStreetChangeIsContinueOnto() {
        Route route = route(town, B, CASTLE); // 11 south (Merchantstreet), 17 south (Castle Way)
        assertEquals(List.of(E_BC, E_C_CASTLE), AStarRouterTest.edges(route));
        List<Maneuver> m = builder.build(route);
        assertEquals(1, m.size());
        assertEquals(Maneuver.Kind.CONTINUE, m.get(0).kind());
        assertEquals("Continue onto Castle Way", m.get(0).text());
        assertEquals(0, m.get(0).bearingChange(), 1e-9);
    }

    @Test
    void straightOnTheSameStreetSaysNothingAndBoundaryNodesAreIgnored() {
        assertTrue(builder.build(route(town, A, E)).isEmpty(), "Keepstreet continues straight through B");
        Route toMill = route(town, E, MILL); // 25, stitch 24, 27: boundary nodes 22 and 23
        assertEquals(2, toMill.nodeIds().size());
        assertTrue(builder.build(toMill).isEmpty());
        assertTrue(builder.build(route(town, A, B)).isEmpty(), "one step: nothing to announce");
    }

    @Test
    void tunnelAddsGoDownAfterTheTurn() {
        Route route = route(town, B, TUNNEL_END); // 11 south, 34 east dipping to y 58
        assertEquals(List.of(E_BC, E_TUNNEL), AStarRouterTest.edges(route));
        List<Maneuver> m = builder.build(route);
        assertEquals(2, m.size());
        assertEquals(Maneuver.Kind.LEFT, m.get(0).kind());
        assertEquals("Take the road on the left", m.get(0).text());
        assertEquals(Maneuver.Kind.DOWN, m.get(1).kind());
        assertEquals("Go down into the tunnel", m.get(1).text());
        assertEquals(m.get(0).along(), m.get(1).along(), 1e-9);
        assertTrue(m.get(1).kind().isLevelChange());
        assertTrue(m.get(0).kind().isTurn());
    }

    @Test
    void bridgeAndStairsPhrases() {
        RoadNetworkSnapshot bridge = NetworkFixture.townBuilder()
            .addEdge(edge(E_TUNNEL, C, TUNNEL_END,
                List.of(p(100, 64, 100), p(120, 70, 100), p(180, 70, 100), p(200, 64, 100))).length(102)
                .profile(PROFILE_ROAD).build()).build();
        List<Maneuver> m = new ManeuverBuilder(bridge).build(route(bridge, B, TUNNEL_END));
        assertEquals(Maneuver.Kind.BRIDGE, m.get(1).kind());
        assertEquals("Cross the bridge", m.get(1).text());

        RoadNetworkSnapshot stairs = NetworkFixture.townBuilder()
            .addNode(node(TUNNEL_END, 200, 70, 100, RoadNodeKind.ENDPOINT, null, 1))
            .addEdge(edge(E_TUNNEL, C, TUNNEL_END, List.of(p(100, 64, 100), p(200, 70, 100))).length(101)
                .profile(PROFILE_ROAD).build()).build();
        List<Maneuver> up = new ManeuverBuilder(stairs).build(route(stairs, B, TUNNEL_END));
        assertEquals(Maneuver.Kind.UP, up.get(1).kind());
        assertEquals("Take the stairs up", up.get(1).text());

        // a 3-block rise is not a level change
        RoadNetworkSnapshot gentle = NetworkFixture.townBuilder()
            .addNode(node(TUNNEL_END, 200, 67, 100, RoadNodeKind.ENDPOINT, null, 1))
            .addEdge(edge(E_TUNNEL, C, TUNNEL_END, List.of(p(100, 64, 100), p(200, 67, 100))).length(101)
                .profile(PROFILE_ROAD).build()).build();
        assertEquals(1, new ManeuverBuilder(gentle).build(route(gentle, B, TUNNEL_END)).size());
    }

    @Test
    void sharpTurnOntoTheDiagonalPath() {
        // D north to A, then the diagonal south-east: 135° to the right
        Route route = router.route(RouteRequest.of(SnapPoint.atNode(town, D), SnapPoint.onEdge(town, E_AC, 20),
            AccessPolicy.ALL_OPEN, RouterParameters.defaults())).route();
        assertEquals(List.of(-E_AD, E_AC), AStarRouterTest.edges(route));
        List<Maneuver> m = builder.build(route);
        assertEquals(1, m.size());
        assertEquals(Maneuver.Kind.SHARP_RIGHT, m.get(0).kind());
        assertEquals(135, m.get(0).bearingChange(), 1e-6);
        assertEquals("Take the path on the right", m.get(0).text());
    }

    @Test
    void slightTurnAndStayOnStreet() {
        RoadNetworkSnapshot s = RoadNetworkSnapshot.builder("w")
            .addStreet(new RoadNetworkSnapshot.Street(1, "High Street"))
            .addNode(node(1, 0, 64, 0, RoadNodeKind.ENDPOINT, null, 1))
            .addNode(node(2, 100, 64, 0, RoadNodeKind.JUNCTION, null, 1))
            .addNode(node(3, 200, 64, 40, RoadNodeKind.ENDPOINT, null, 1))
            .addNode(node(4, 100, 64, -50, RoadNodeKind.ENDPOINT, null, 1))
            .addEdge(edge(1, 1, 2, line(0, 64, 0, 100, 64, 0)).street(1).build())
            .addEdge(edge(2, 2, 3, line(100, 64, 0, 200, 64, 40)).street(1).build())
            .addEdge(edge(3, 2, 4, line(100, 64, 0, 100, 64, -50)).build()).build();
        List<Maneuver> m = new ManeuverBuilder(s).build(route(s, 1, 3));
        assertEquals(1, m.size());
        assertEquals(Maneuver.Kind.SLIGHT_RIGHT, m.get(0).kind());
        assertEquals(21.8, m.get(0).bearingChange(), 0.1);
        assertEquals("Turn slightly right to stay on High Street", m.get(0).text());
        List<Maneuver> north = new ManeuverBuilder(s).build(route(s, 1, 4));
        assertEquals(Maneuver.Kind.LEFT, north.get(0).kind());
        assertEquals("Take the road on the left", north.get(0).text());
    }

    @Test
    void turnBands() {
        assertNull(ManeuverBuilder.turnKind(0));
        assertNull(ManeuverBuilder.turnKind(19.9));
        assertEquals(Maneuver.Kind.SLIGHT_LEFT, ManeuverBuilder.turnKind(-20));
        assertEquals(Maneuver.Kind.SLIGHT_RIGHT, ManeuverBuilder.turnKind(59.9));
        assertEquals(Maneuver.Kind.RIGHT, ManeuverBuilder.turnKind(60));
        assertEquals(Maneuver.Kind.LEFT, ManeuverBuilder.turnKind(-120));
        assertEquals(Maneuver.Kind.SHARP_LEFT, ManeuverBuilder.turnKind(-120.1));
        assertEquals(Maneuver.Kind.SHARP_RIGHT, ManeuverBuilder.turnKind(179));
        assertEquals("straight", new Maneuver(Maneuver.Kind.DOWN, new double[] {0, 0, 0}, 0, 0, null, "x").direction());
    }
}

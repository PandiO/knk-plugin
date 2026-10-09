package net.knightsandkings.knk.paper.navigation;

import java.util.EnumSet;
import java.util.List;
import java.util.OptionalInt;

import net.knightsandkings.knk.core.domain.roads.RoadClass;
import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeFlag;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeSource;
import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import net.knightsandkings.knk.core.roads.route.AStarRouter;
import net.knightsandkings.knk.core.roads.route.AccessPolicy;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;
import net.knightsandkings.knk.core.roads.route.Route;
import net.knightsandkings.knk.core.roads.route.RouteRequest;
import net.knightsandkings.knk.core.roads.route.RouterParameters;
import net.knightsandkings.knk.core.roads.route.SnapPoint;

/**
 * A tiny road network for the Phase 4 tests (knk-paper cannot see knk-core's {@code NetworkFixture}):
 * Main Street A(0,64,0) → B(100,64,0) → C(200,64,0), with gate door {@link #GATE_DOOR} on B→C, a
 * detour B → D(100,64,100) → C' (200,64,100) → C (300 blocks instead of 100), and the Kardenna
 * Castle edge C → CASTLE(200,64,200) tagged with region {@link #CASTLE_REGION}. Everything is floor y 64.
 */
final class NavigationTestNetwork {

    static final String WORLD = "world";
    static final int A = 1, B = 2, C = 3, D = 4, C2 = 5, CASTLE = 6;
    static final int E_AB = 10, E_BC = 11, E_BD = 12, E_DC2 = 13, E_C2C = 14, E_C_CASTLE = 15;
    static final int GATE_DOOR = 7;
    static final String CASTLE_REGION = "kardenna_castle";
    static final int CASTLE_DOMAIN = 42;
    static final int STREET_MAIN = 1;

    final RoadNetworkSnapshot snapshot;

    NavigationTestNetwork() {
        RoadNetworkSnapshot.Builder b = RoadNetworkSnapshot.builder(WORLD);
        b.addProfile(new RoadNetworkSnapshot.Profile(1, "Main", RoadClass.MAIN, 1.0));
        b.addStreet(new RoadNetworkSnapshot.Street(STREET_MAIN, "Main Street"));
        b.addNode(new RoadNode(A, 0, 64, 0, RoadNodeKind.JUNCTION, "West End", 1));
        b.addNode(new RoadNode(B, 100, 64, 0, RoadNodeKind.JUNCTION, null, 1));
        b.addNode(new RoadNode(C, 200, 64, 0, RoadNodeKind.JUNCTION, "Cinix Keep", 1));
        b.addNode(new RoadNode(D, 100, 64, 100, RoadNodeKind.JUNCTION, null, 1));
        b.addNode(new RoadNode(C2, 200, 64, 100, RoadNodeKind.JUNCTION, null, 1));
        b.addNode(new RoadNode(CASTLE, 200, 64, 200, RoadNodeKind.ENDPOINT, "Kardenna Castle", 1));
        b.addEdge(edge(E_AB, A, B, 0, 0, 100, 0, List.of(), List.of(), STREET_MAIN));
        b.addEdge(edge(E_BC, B, C, 100, 0, 200, 0, List.of(GATE_DOOR), List.of(), STREET_MAIN));
        b.addEdge(edge(E_BD, B, D, 100, 0, 100, 100, List.of(), List.of(), -1));
        b.addEdge(edge(E_DC2, D, C2, 100, 100, 200, 100, List.of(), List.of(), -1));
        b.addEdge(edge(E_C2C, C2, C, 200, 100, 200, 0, List.of(), List.of(), -1));
        b.addEdge(edge(E_C_CASTLE, C, CASTLE, 200, 0, 200, 200, List.of(), List.of(CASTLE_REGION), -1));
        this.snapshot = b.build();
    }

    private static RoadEdge edge(int id, int from, int to, int x0, int z0, int x1, int z1, List<Integer> doors,
                                 List<String> regions, int street) {
        List<int[]> geometry = List.of(new int[] {x0, 64, z0}, new int[] {x1, 64, z1});
        double length = Math.hypot(x1 - x0, z1 - z0);
        return new RoadEdge(id, from, to, geometry, length, 3, OptionalInt.of(1),
            street < 0 ? OptionalInt.empty() : OptionalInt.of(street), 1.0, EnumSet.noneOf(RoadEdgeFlag.class),
            doors, regions.isEmpty() ? List.of() : List.of(CASTLE_DOMAIN), regions, RoadEdgeSource.DETECTED, false);
    }

    /** A → C along Main Street with everything open. */
    Route routeAlongMainStreet() {
        RouteRequest request = RouteRequest.of(SnapPoint.atNode(snapshot, A), SnapPoint.atNode(snapshot, C),
            AccessPolicy.ALL_OPEN, RouterParameters.defaults());
        return new AStarRouter(snapshot).route(request).route();
    }
}

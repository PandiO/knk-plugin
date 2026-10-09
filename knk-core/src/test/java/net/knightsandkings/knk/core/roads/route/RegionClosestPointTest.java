package net.knightsandkings.knk.core.roads.route;

import org.junit.jupiter.api.Test;

import java.util.List;

import static net.knightsandkings.knk.core.roads.route.NetworkFixture.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionClosestPointTest {

    private final RoadNetworkSnapshot town = NetworkFixture.town();
    /** A box around the castle node: the castle road enters at z 180, the oneway road leaves at x 80. */
    private final RegionShape castle = RegionShape.cuboid(80, 60, 180, 120, 70, 220);

    @Test
    void goalsAreTheCrossingPointsIntoTheRegion() {
        List<SnapPoint> goals = RegionClosestPoint.goals(castle, town);
        assertEquals(2, goals.size());
        SnapPoint viaCastleRoad = goals.get(0);
        assertEquals(E_C_CASTLE, viaCastleRoad.edgeId());
        assertEquals(80, viaCastleRoad.along(), 0.05, "bisected to the region edge, not the next vertex");
        assertEquals(180, viaCastleRoad.z(), 0.05);
        SnapPoint viaOneway = goals.get(1);
        assertEquals(E_CASTLE20, viaOneway.edgeId());
        assertEquals(20, viaOneway.along(), 0.05, "leaving the region 20 blocks along edge 19");
        assertEquals(80, viaOneway.x(), 0.05);
    }

    @Test
    void routerStopsWhereTheRoadEntersTheRegion() {
        RouteResult r = new AStarRouter(town).route(RouteRequest.of(SnapPoint.atNode(town, A),
            RegionClosestPoint.goals(castle, town), AccessPolicy.ALL_OPEN, RouterParameters.defaults()));
        assertTrue(r.isFound());
        assertEquals(List.of(E_AC, E_C_CASTLE), AStarRouterTest.edges(r.route()));
        assertEquals(150 + 80, r.route().length(), 0.05);
        assertEquals(180, r.route().end().z(), 0.05);
        // from node 20 the oneway edge cannot be entered: the route goes round through C
        RouteResult from20 = new AStarRouter(town).route(RouteRequest.of(SnapPoint.atNode(town, TWENTY),
            RegionClosestPoint.goals(castle, town), AccessPolicy.ALL_OPEN, RouterParameters.defaults()));
        assertEquals(List.of(-E_D20, -E_CD, E_C_CASTLE), AStarRouterTest.edges(from20.route()));
    }

    @Test
    void nothingInsideFallsBackToTheClosestNetworkPoint() {
        RegionShape far = RegionShape.cuboid(260, 60, 250, 280, 70, 270);
        List<SnapPoint> goals = RegionClosestPoint.goals(far, town);
        assertEquals(1, goals.size());
        assertEquals(E_TUNNEL, goals.get(0).edgeId(), "the tunnel's east end (200, 100) is nearest");
        assertEquals(200, goals.get(0).x(), 1e-9);
        assertEquals(100, goals.get(0).z(), 1e-9);
        assertTrue(RegionClosestPoint.goals(far, RoadNetworkSnapshot.empty("w")).isEmpty());
    }

    @Test
    void roadEntirelyInsideTheRegionUsesItsVertices() {
        RegionShape whole = RegionShape.cuboid(-100, 0, -100, 700, 200, 300);
        List<SnapPoint> goals = RegionClosestPoint.goals(whole, town);
        assertTrue(goals.size() >= town.edgeCount(), "every vertex of every edge");
        assertTrue(goals.stream().allMatch(g -> whole.containsFloor(g.x(), g.y(), g.z())));
    }

    @Test
    void polygonRegionCrossingIsFoundOnADiagonalEdge() {
        // triangle over the diagonal path's middle: (30,30)-(80,30)-(30,80); edge 15 runs (0,0)→(50,50)→(100,100)
        RegionShape tri = RegionShape.polygon(List.of(new double[] {30, 30}, new double[] {80, 30},
            new double[] {30, 80}), 60, 70);
        List<SnapPoint> goals = RegionClosestPoint.crossings(tri, town);
        assertEquals(2, goals.size(), "in at (30,30), out at (55,55)");
        assertEquals(E_AC, goals.get(0).edgeId());
        assertEquals(30, goals.get(0).x(), 0.05);
        assertEquals(55, goals.get(1).x(), 0.05);
    }
}

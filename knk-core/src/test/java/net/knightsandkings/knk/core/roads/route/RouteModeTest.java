package net.knightsandkings.knk.core.roads.route;

import net.knightsandkings.knk.core.domain.roads.RoadClass;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Off-road phase 4 (PandiO/knk-workspace#707, DESIGN § 5.4, A09, A11): the three route modes on the road A*, their
 * cost factors, the admissible heuristic per mode and the words.
 */
class RouteModeTest {

    // ---- a scene where the three modes differ (A09, road part): RouteModeScenes.threeWays --------------------------

    static final int S = RouteModeScenes.S, T = RouteModeScenes.T, M_SHORT = RouteModeScenes.M_SHORT,
        M_MAIN = RouteModeScenes.M_MAIN, M_ROAD = RouteModeScenes.M_ROAD;
    static final int E_S1 = RouteModeScenes.E_S1, E_S2 = RouteModeScenes.E_S2;

    static RoadNetworkSnapshot threeWays(double laneProfileMultiplier, double mainEdgeMultiplier) {
        return RouteModeScenes.threeWays(laneProfileMultiplier, mainEdgeMultiplier);
    }

    private static RouteResult route(RoadNetworkSnapshot net, RouteMode mode) {
        RouteRequest request = RouteRequest.of(SnapPoint.atNode(net, S), SnapPoint.atNode(net, T), AccessPolicy.ALL_OPEN,
            RouterParameters.defaults());
        return new AStarRouter(net).route(mode == null ? request : request.withMode(mode));
    }

    private static List<Integer> nodes(RouteResult result) {
        assertTrue(result.isFound(), result.toString());
        return result.route().nodeIds();
    }

    @Test
    void theThreeModesTakeThreeDifferentRoads() {
        RoadNetworkSnapshot net = threeWays(1.5, 2.0);

        RouteResult shortest = route(net, RouteMode.SHORTEST);
        assertEquals(List.of(M_SHORT), nodes(shortest), "length only: the 100-block lane");
        assertEquals(100, shortest.route().cost(), 1e-9);
        assertEquals(RouteMode.SHORTEST, shortest.route().mode());

        RouteResult efficient = route(net, RouteMode.EFFICIENT);
        assertEquals(List.of(M_MAIN), nodes(efficient), "class costs: 110 x 0.9 = 99 beats 100 x 1.15 = 115");
        assertEquals(99, efficient.route().cost(), 1e-9);
        assertEquals(110, efficient.route().length(), 1e-9);

        RouteResult preferred = route(net, RouteMode.PREFERRED);
        assertEquals(List.of(M_ROAD), nodes(preferred), "with the multipliers: 120 beats 172.5 and 198");
        assertEquals(120, preferred.route().cost(), 1e-9);

        RouteResult byDefault = route(net, null);
        assertEquals(List.of(M_ROAD), nodes(byDefault), "no mode named: preferred, today's routing");
        assertEquals(RouteMode.PREFERRED, byDefault.route().mode());
        assertEquals(preferred.route().cost(), byDefault.route().cost(), 0.0);
    }

    @Test
    void withoutMultipliersPreferredAndEfficientAreTheSameRoute() {
        // A11 (road part): no road cost multipliers and no preference landmarks - preferred behaves like efficient
        RoadNetworkSnapshot plain = threeWays(1.0, 1.0);
        RouteResult preferred = route(plain, RouteMode.PREFERRED);
        RouteResult efficient = route(plain, RouteMode.EFFICIENT);
        assertEquals(nodes(efficient), nodes(preferred));
        assertEquals(efficient.route().cost(), preferred.route().cost(), 0.0);
        assertEquals(efficient.route().polyline().size(), preferred.route().polyline().size());

        RoadNetworkSnapshot town = NetworkFixture.town();
        AStarRouter router = new AStarRouter(town);
        int[] ids = {NetworkFixture.A, NetworkFixture.B, NetworkFixture.C, NetworkFixture.D, NetworkFixture.CASTLE,
            NetworkFixture.TWENTY};
        for (int from : ids) {
            for (int to : ids) {
                if (from == to) {
                    continue;
                }
                RouteRequest request = RouteRequest.of(SnapPoint.atNode(town, from), SnapPoint.atNode(town, to),
                    AccessPolicy.ALL_OPEN, RouterParameters.defaults());
                RouteResult p = router.route(request.withMode(RouteMode.PREFERRED));
                RouteResult e = router.route(request.withMode(RouteMode.EFFICIENT));
                assertEquals(p.status(), e.status(), from + " -> " + to);
                if (p.isFound()) {
                    assertEquals(p.route().steps().stream().map(s -> s.edge().id()).toList(),
                        e.route().steps().stream().map(s -> s.edge().id()).toList(), from + " -> " + to);
                    assertEquals(p.route().cost(), e.route().cost(), 1e-9, from + " -> " + to);
                }
            }
        }
    }

    @Test
    void costFactorsPerMode() {
        RoadNetworkSnapshot net = threeWays(1.5, 2.0);
        Map<RoadClass, Double> classCost = RouterParameters.defaultClassCost();
        var lane = net.edge(E_S1).orElseThrow();
        var main = net.edge(E_S2).orElseThrow();
        assertEquals(1.0, net.costFactor(lane, classCost, RouteMode.SHORTEST), 0.0);
        assertEquals(1.15, net.costFactor(lane, classCost, RouteMode.EFFICIENT), 1e-12);
        assertEquals(1.5 * 1.15, net.costFactor(lane, classCost, RouteMode.PREFERRED), 1e-12);
        assertEquals(0.9, net.costFactor(main, classCost, RouteMode.EFFICIENT), 1e-12);
        assertEquals(2.0 * 0.9, net.costFactor(main, classCost, RouteMode.PREFERRED), 1e-12);
        assertEquals(net.costFactor(main, classCost), net.costFactor(main, classCost, RouteMode.PREFERRED), 0.0,
            "the old method is the default mode");
        assertEquals(55 * 1.8, net.edgeCost(main, classCost, RouteMode.PREFERRED), 1e-9);
    }

    @Test
    void theHeuristicUsesTheSmallestMultiplierInForce() {
        // DESIGN § 5.4.4: admissible in every mode - never above the cheapest factor of that mode
        RoadNetworkSnapshot net = threeWays(1.5, 2.0);
        Map<RoadClass, Double> classCost = RouterParameters.defaultClassCost();
        assertEquals(1.0, net.minCostFactor(classCost, RouteMode.SHORTEST), 0.0);
        assertEquals(0.9, net.minCostFactor(classCost, RouteMode.EFFICIENT), 1e-12, "the Main class");
        assertEquals(1.0, net.minCostFactor(classCost, RouteMode.PREFERRED), 1e-12, "the town road: 1.0 x 1.0");
        assertEquals(net.minCostFactor(classCost), net.minCostFactor(classCost, RouteMode.PREFERRED), 0.0);

        // a cheap multiplier below 1 lowers the bound: the search still finds the cheapest route
        RoadNetworkSnapshot cheapMain = threeWays(1.5, 0.5);
        assertEquals(0.45, cheapMain.minCostFactor(classCost, RouteMode.PREFERRED), 1e-12);
        RouteResult preferred = route(cheapMain, RouteMode.PREFERRED);
        assertEquals(List.of(M_MAIN), nodes(preferred), "110 x 0.9 x 0.5 = 49.5");
        assertEquals(49.5, preferred.route().cost(), 1e-9);
        for (RouteMode mode : RouteMode.values()) {
            double factor = cheapMain.minCostFactor(classCost, mode);
            for (var e : cheapMain.edges()) {
                assertTrue(cheapMain.costFactor(e, classCost, mode) >= factor - 1e-12, mode + " edge " + e.id());
                assertTrue(e.length() * factor <= cheapMain.edgeCost(e, classCost, mode) + 1e-9,
                    "the bound is never above an edge's cost: " + mode);
            }
        }
    }

    @Test
    void aRebuiltRouteKeepsItsMode() {
        RoadNetworkSnapshot net = threeWays(1.5, 2.0);
        Route efficient = route(net, RouteMode.EFFICIENT).route();
        Route head = efficient.truncated(net, 1);
        assertEquals(RouteMode.EFFICIENT, head.mode());
        assertEquals(55 * 0.9, head.cost(), 1e-9, "the step cost of the mode, not today's 55 x 1.8");
        Route reVerdicted = efficient.withVerdicts(net, AccessPolicy.ALL_OPEN);
        assertEquals(efficient.cost(), reVerdicted.cost(), 1e-9);
        assertEquals(RouteMode.EFFICIENT, reVerdicted.mode());
    }

    @Test
    void theWordsAndTheirNodes() {
        assertEquals(Optional.of(RouteMode.SHORTEST), RouteMode.fromWord("shortest"));
        assertEquals(Optional.of(RouteMode.EFFICIENT), RouteMode.fromWord("EFFICIENT"));
        assertEquals(Optional.of(RouteMode.PREFERRED), RouteMode.fromWord(" Preferred "));
        assertEquals(Optional.empty(), RouteMode.fromWord("spawn"));
        assertEquals(Optional.empty(), RouteMode.fromWord(null));
        assertEquals("knk.navigate.mode.shortest", RouteMode.SHORTEST.node());
        assertEquals("knk.navigate.mode.efficient", RouteMode.EFFICIENT.node());
        assertEquals("knk.navigate.mode.preferred", RouteMode.PREFERRED.node());
        assertEquals(RouteMode.PREFERRED, RouteMode.DEFAULT, "O24: the default is today's routing");
        assertTrue(RouteMode.PREFERRED.usesPreferenceWeights(), "the seam for #709");
        assertTrue(!RouteMode.EFFICIENT.usesPreferenceWeights() && !RouteMode.SHORTEST.usesPreferenceWeights());
    }

    @Test
    void aRequestKeepsItsModeThroughItsCopies() {
        RoadNetworkSnapshot net = threeWays(1.5, 2.0);
        RouteRequest request = RouteRequest.of(SnapPoint.atNode(net, S), SnapPoint.atNode(net, T), AccessPolicy.ALL_OPEN,
            RouterParameters.defaults());
        assertEquals(RouteMode.DEFAULT, request.mode());
        RouteRequest shortest = request.withMode(RouteMode.SHORTEST);
        assertEquals(RouteMode.SHORTEST, shortest.withPolicy(AccessPolicy.ALL_OPEN).mode());
        assertEquals(RouteMode.SHORTEST, shortest.withStart(SnapPoint.atNode(net, M_SHORT)).mode());
        assertNotEquals(request, shortest);
    }

    @Test
    void aLinkCostsItsLengthInShortestAndItsWalkCostOtherwise() {
        WalkLink link = WalkLink.ofWalkPath(WalkLinkId.of(S, T), "world", new double[] {0, 64, 0},
            new double[] {20, 64, 0}, List.of(new double[] {0.5, 64, 0.5}, new double[] {20.5, 64, 0.5}), 20, 35,
            List.of(), List.of());
        assertEquals(link.length(), RouteMode.SHORTEST.linkCost(link), 0.0);
        assertEquals(link.walkCost(), RouteMode.EFFICIENT.linkCost(link), 0.0);
        assertEquals(link.walkCost(), RouteMode.PREFERRED.linkCost(link), 0.0);
    }
}

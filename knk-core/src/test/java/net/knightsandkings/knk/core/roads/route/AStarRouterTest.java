package net.knightsandkings.knk.core.roads.route;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.roads.RoadClass;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeFlag;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import net.knightsandkings.knk.core.regions.DomainAccessEvaluator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static net.knightsandkings.knk.core.roads.route.NetworkFixture.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AStarRouterTest {

    private final RoadNetworkSnapshot town = NetworkFixture.town();
    private final RouterParameters params = RouterParameters.defaults();
    private final AStarRouter router = new AStarRouter(town);

    private RouteRequest request(SnapPoint start, SnapPoint goal, AccessPolicy policy) {
        return RouteRequest.of(start, goal, policy, params);
    }

    private RouteRequest request(int startNode, int goalNode) {
        return request(SnapPoint.atNode(town, startNode), SnapPoint.atNode(town, goalNode), AccessPolicy.ALL_OPEN);
    }

    /** Signed edge ids in route order: negative = walked To → From. */
    static List<Integer> edges(Route route) {
        List<Integer> ids = new ArrayList<>();
        for (Route.Step s : route.steps()) {
            ids.add(s.forward() ? s.edge().id() : -s.edge().id());
        }
        return ids;
    }

    static GateAvailability gate(AnimationState state, boolean passThrough, boolean canPass) {
        GateAvailability.GateView view = new GateAvailability.GateView(GATE_DOOR, "West Gate", state, false, false,
            passThrough, false, false);
        return new GateAvailability(id -> id == GATE_DOOR ? Optional.of(view) : Optional.empty(), id -> canPass);
    }

    static DomainAvailability castle(Boolean allowEntry, Boolean allowExit, Set<String> currentRegions) {
        return new DomainAvailability(new DomainAccessEvaluator(),
            AccessPolicyTest.lookup(AccessPolicyTest.domain(CASTLE_DOMAIN, "Kardenna Castle", CASTLE_REGION, allowEntry,
                allowExit)), currentRegions, false);
    }

    // ---- shortest path and class costs ---------------------------------------------------------

    @Test
    void shortestPathFollowsClassCosts() {
        RouteResult r = router.route(request(A, C));
        assertTrue(r.isFound());
        assertEquals(List.of(E_AC), edges(r.route()), "150 × 1.15 = 172.5 beats 100 × 0.9 + 100 = 190");
        assertEquals(150, r.route().length(), 1e-9);
        assertEquals(172.5, r.route().cost(), 1e-9);

        RouteRequest dearPaths = request(A, C).withPolicy(AccessPolicy.ALL_OPEN);
        RouteRequest costly = new RouteRequest(dearPaths.start(), dearPaths.goals(), AccessPolicy.ALL_OPEN,
            Map.of(RoadClass.MAIN, 0.9, RoadClass.ROAD, 1.0, RoadClass.PATH, 1.5));
        RouteResult r2 = router.route(costly);
        assertEquals(List.of(E_AB, E_BC), edges(r2.route()), "paths at 1.5: the diagonal costs 225, via B 190");
        assertEquals(200, r2.route().length(), 1e-9);
        assertEquals(190, r2.route().cost(), 1e-9);
        assertEquals(List.of(B), r2.route().nodeIds());
    }

    @Test
    void edgeCostMultiplierIsHonoured() {
        RoadNetworkSnapshot tuned = NetworkFixture.townBuilder()
            .addEdge(edge(E_AC, A, C, List.of(p(0, 64, 0), p(50, 64, 50), p(100, 64, 100))).length(150)
                .profile(PROFILE_PATH).cost(3.0).build())
            .build();
        RouteResult r = new AStarRouter(tuned).route(RouteRequest.of(SnapPoint.atNode(tuned, A),
            SnapPoint.atNode(tuned, C), AccessPolicy.ALL_OPEN, params));
        assertEquals(List.of(E_AB, E_BC), edges(r.route()));
    }

    // ---- oneway --------------------------------------------------------------------------------

    @Test
    void onewayEdgeIsWalkedFromToOnly() {
        // D → castle: via 20 would be 90 + 90 but edge 19 only runs castle → 20
        RouteResult r = router.route(request(D, CASTLE));
        assertEquals(List.of(E_CD == 12 ? -E_CD : E_CD, E_C_CASTLE), edges(r.route()));
        assertEquals(200, r.route().length(), 1e-9);
        // castle → 20 uses it
        assertEquals(List.of(E_CASTLE20), edges(router.route(request(CASTLE, TWENTY)).route()));
        // 20 → castle must go round
        RouteResult back = router.route(request(TWENTY, CASTLE));
        assertEquals(List.of(-E_D20, -E_CD, E_C_CASTLE), edges(back.route()));
        assertEquals(300, back.route().length(), 1e-9);
    }

    @Test
    void virtualStartOnAOnewayEdgeCannotTurnBack() {
        // on edge 19 (castle → 20) 50 blocks in, goal 20 blocks in: behind us → all the way round
        SnapPoint start = SnapPoint.onEdge(town, E_CASTLE20, 50);
        SnapPoint goal = SnapPoint.onEdge(town, E_CASTLE20, 20);
        RouteResult r = router.route(request(start, goal, AccessPolicy.ALL_OPEN));
        assertTrue(r.isFound());
        assertEquals(List.of(E_CASTLE20, -E_D20, -E_CD, E_C_CASTLE, E_CASTLE20), edges(r.route()));
        assertEquals(50 + 100 + 100 + 100 + 20, r.route().length(), 1e-9);
        assertEquals(List.of(TWENTY, D, C, CASTLE), r.route().nodeIds());
    }

    // ---- virtual nodes ---------------------------------------------------------------------------

    @Test
    void startAndGoalOnTheSameEdgeConnectDirectly() {
        SnapPoint start = SnapPoint.onEdge(town, E_AB, 20);
        Route forward = router.route(request(start, SnapPoint.onEdge(town, E_AB, 80), AccessPolicy.ALL_OPEN)).route();
        assertEquals(List.of(E_AB), edges(forward));
        assertEquals(60, forward.length(), 1e-9);
        assertEquals(20, forward.steps().get(0).entryAlong(), 1e-9);
        assertEquals(80, forward.steps().get(0).exitAlong(), 1e-9);
        Route backward = router.route(request(start, SnapPoint.onEdge(town, E_AB, 10), AccessPolicy.ALL_OPEN)).route();
        assertEquals(List.of(-E_AB), edges(backward));
        assertEquals(10, backward.length(), 1e-9);
        assertEquals(2, backward.polyline().size());
        assertEquals(20, backward.polyline().get(0)[0], 1e-9);
        assertEquals(10, backward.polyline().get(1)[0], 1e-9);
    }

    @Test
    void midEdgeStartAndGoalProduceTrimmedPolylineAndDistances() {
        SnapPoint start = SnapPoint.onEdge(town, E_AB, 30);
        SnapPoint goal = SnapPoint.onEdge(town, E_BC, 40);
        Route route = router.route(request(start, goal, AccessPolicy.ALL_OPEN)).route();
        assertEquals(List.of(E_AB, E_BC), edges(route));
        assertEquals(110, route.length(), 1e-9);
        assertEquals(70 * 0.9 + 40, route.cost(), 1e-9);
        List<double[]> pl = route.polyline();
        assertEquals(3, pl.size());
        assertArrayEq(new double[] {30, 64, 0}, pl.get(0));
        assertArrayEq(new double[] {100, 64, 0}, pl.get(1));
        assertArrayEq(new double[] {100, 64, 40}, pl.get(2));
        assertEquals(0, route.steps().get(0).startDistance(), 1e-9);
        assertEquals(70, route.steps().get(1).startDistance(), 1e-9);
        assertEquals(70, route.steps().get(0).length(), 1e-9);
        assertEquals(List.of(B), route.nodeIds());
        assertEquals(start, route.start());
        assertEquals(goal, route.end());
    }

    @Test
    void startEqualToGoalIsAnEmptyRoute() {
        SnapPoint at = SnapPoint.onEdge(town, E_AB, 30);
        RouteResult r = router.route(request(at, at, AccessPolicy.ALL_OPEN));
        assertTrue(r.isFound());
        assertTrue(r.route().isEmpty());
        assertEquals(0, r.route().length());
        assertEquals(1, r.route().polyline().size());
    }

    @Test
    void routeAcrossTileBoundaryUsesTheStitchEdge() {
        Route route = router.route(request(E, MILL)).route();
        assertEquals(List.of(E_E_BOUNDARY, E_STITCH, E_BOUNDARY_MILL), edges(route));
        assertEquals(400, route.length(), 1e-9);
        assertEquals(List.of(BOUNDARY_W, BOUNDARY_E), route.nodeIds());
    }

    // ---- multi-goal ------------------------------------------------------------------------------

    @Test
    void multiGoalStopsAtTheCheapestGoal() {
        RouteRequest req = RouteRequest.of(SnapPoint.atNode(town, A),
            List.of(SnapPoint.atNode(town, E), SnapPoint.onEdge(town, E_CD, 0)), AccessPolicy.ALL_OPEN, params);
        Route route = router.route(req).route();
        assertEquals(List.of(E_AC), edges(route), "C (172.5) beats E (180)");
        assertEquals(100, route.end().x(), 1e-9);
        assertEquals(100, route.end().z(), 1e-9);
        // a goal in another component is ignored as long as one is reachable
        RouteRequest mixed = RouteRequest.of(SnapPoint.atNode(town, A),
            List.of(SnapPoint.onEdge(town, E_BRIDGE, 10), SnapPoint.atNode(town, B)), AccessPolicy.ALL_OPEN, params);
        assertEquals(List.of(E_AB), edges(router.route(mixed).route()));
    }

    // ---- components -------------------------------------------------------------------------------

    @Test
    void differentComponentsAreRefusedWithoutSearching() {
        AccessPolicy exploding = e -> {
            throw new AssertionError("must not search");
        };
        RouteResult r = router.route(request(SnapPoint.onEdge(town, E_BRIDGE, 10), SnapPoint.atNode(town, B),
            exploding));
        assertEquals(RouteResult.Status.DIFFERENT_COMPONENTS, r.status());
        assertTrue(r.routeToGuide().isEmpty());
        assertEquals(RouteResult.Status.DIFFERENT_COMPONENTS,
            router.routeOrExplain(request(SnapPoint.onEdge(town, E_BRIDGE, 10), SnapPoint.atNode(town, B), exploding))
                .status());
    }

    @Test
    void unreachableWithinAComponentIsNoRoute() {
        // two islands the API would put in one component: nothing to explain either
        RoadNetworkSnapshot islands = RoadNetworkSnapshot.builder("w")
            .addNode(node(1, 0, 64, 0, RoadNodeKind.ENDPOINT, null, 1)).addNode(node(2, 10, 64, 0, RoadNodeKind.ENDPOINT, null, 1))
            .addNode(node(3, 50, 64, 0, RoadNodeKind.ENDPOINT, null, 1)).addNode(node(4, 60, 64, 0, RoadNodeKind.ENDPOINT, null, 1))
            .addEdge(edge(1, 1, 2, line(0, 64, 0, 10, 64, 0)).build())
            .addEdge(edge(2, 3, 4, line(50, 64, 0, 60, 64, 0)).build()).build();
        AStarRouter r = new AStarRouter(islands);
        RouteRequest req = RouteRequest.of(SnapPoint.atNode(islands, 1), SnapPoint.atNode(islands, 4),
            AccessPolicy.ALL_OPEN, params);
        assertEquals(RouteResult.Status.NO_ROUTE, r.route(req).status());
        assertEquals(RouteResult.Status.NO_ROUTE, r.routeOrExplain(req).status());
        assertThrows(IllegalArgumentException.class, () -> router.route(RouteRequest.of(SnapPoint.atNode(islands, 1),
            SnapPoint.atNode(town, A), AccessPolicy.ALL_OPEN, params)));
    }

    // ---- gates ------------------------------------------------------------------------------------

    @Test
    void closedGateIsExplainedWithAPartialRouteToTheGate() {
        RouteRequest req = request(SnapPoint.atNode(town, A), SnapPoint.atNode(town, E),
            gate(AnimationState.CLOSED, false, false));
        assertEquals(RouteResult.Status.NO_ROUTE, router.route(req).status());
        RouteResult r = router.routeOrExplain(req);
        assertEquals(RouteResult.Status.BLOCKED, r.status());
        BlockedExplainer.Explanation ex = r.explanation();
        assertTrue(ex.isGateBlock());
        assertEquals("the West Gate is closed", ex.reason());
        assertEquals(E_BE, ex.blockedEdge().id());
        assertEquals(List.of(E_AB), edges(ex.partialRoute()));
        assertEquals(List.of(E_AB, E_BE), edges(ex.fullRoute()));
        assertEquals(100, ex.partialRoute().end().x(), 1e-9, "guided to B, where the gate edge starts");
        assertEquals(ex.partialRoute(), r.routeToGuide().orElseThrow());
    }

    @Test
    void passThroughGateGivesARouteWithAHint() {
        RouteResult r = router.route(request(SnapPoint.atNode(town, A), SnapPoint.atNode(town, E),
            gate(AnimationState.CLOSED, true, true)));
        assertTrue(r.isFound());
        assertEquals(List.of(E_AB, E_BE), edges(r.route()));
        List<Route.Step> hints = r.route().passThroughSteps();
        assertEquals(1, hints.size());
        assertEquals(E_BE, hints.get(0).edge().id());
        assertEquals("right-click the West Gate to pass", hints.get(0).verdict().message());
        assertTrue(r.route().steps().get(0).verdict().isOpen());
    }

    @Test
    void blockedStartEdgeCannotBeLeftAndIsExplainedWithAnEmptyPartialRoute() {
        RouteRequest req = request(SnapPoint.onEdge(town, E_BE, 50), SnapPoint.atNode(town, A),
            gate(AnimationState.CLOSED, false, false));
        RouteResult r = router.routeOrExplain(req);
        assertEquals(RouteResult.Status.BLOCKED, r.status());
        assertTrue(r.route().isEmpty());
        assertEquals(E_BE, r.explanation().blockedEdge().id());
    }

    // ---- domains -----------------------------------------------------------------------------------

    @Test
    void entryDeniedDestinationEndsTheRouteAtTheRegionEdgeWithTheReason() {
        RouteRequest req = request(SnapPoint.atNode(town, A), SnapPoint.atNode(town, CASTLE),
            castle(false, null, Set.of()));
        RouteResult r = router.routeOrExplain(req);
        assertEquals(RouteResult.Status.BLOCKED, r.status());
        BlockedExplainer.Explanation ex = r.explanation();
        assertTrue(ex.isDomainBlock());
        assertEquals("you may not enter Kardenna Castle", ex.reason());
        assertEquals("42", ex.cause().id());
        assertEquals(E_C_CASTLE, ex.blockedEdge().id());
        assertEquals(List.of(E_AC), edges(ex.partialRoute()));
        assertEquals(100, ex.partialRoute().end().z(), 1e-9, "ends at C, the last node outside the castle");
    }

    @Test
    void exitDeniedDomainKeepsTheRouteInsideIt() {
        // standing on the castle road, 50 blocks in; may not leave; wants to go to A
        RouteRequest req = request(SnapPoint.onEdge(town, E_C_CASTLE, 50), SnapPoint.atNode(town, A),
            castle(null, false, Set.of(CASTLE_REGION)));
        RouteResult r = router.routeOrExplain(req);
        assertEquals(RouteResult.Status.BLOCKED, r.status());
        assertEquals("you may not leave Kardenna Castle", r.explanation().reason());
        assertEquals(List.of(-E_C_CASTLE), edges(r.route()), "as far as the castle road goes");
        assertEquals(50, r.route().length(), 1e-9);
        // inside the castle everything still works
        RouteResult inside = router.route(request(SnapPoint.onEdge(town, E_C_CASTLE, 50),
            SnapPoint.atNode(town, CASTLE), castle(null, false, Set.of(CASTLE_REGION))));
        assertTrue(inside.isFound());
        assertEquals(List.of(E_C_CASTLE), edges(inside.route()));
    }

    @Test
    void closedFlagIsRoutedAroundOrExplained() {
        RoadNetworkSnapshot closedDiagonal = NetworkFixture.townBuilder()
            .addEdge(edge(E_AC, A, C, List.of(p(0, 64, 0), p(50, 64, 50), p(100, 64, 100))).length(150)
                .profile(PROFILE_PATH).flags(RoadEdgeFlag.CLOSED).build()).build();
        AStarRouter r = new AStarRouter(closedDiagonal);
        StaticFlagsAvailability flags = new StaticFlagsAvailability();
        Route around = r.route(RouteRequest.of(SnapPoint.atNode(closedDiagonal, A), SnapPoint.atNode(closedDiagonal, C),
            flags, params)).route();
        assertEquals(List.of(E_AB, E_BC), edges(around));
        // the mill road closed: nothing else leads there
        RoadNetworkSnapshot closedMill = NetworkFixture.townBuilder()
            .addEdge(edge(E_BOUNDARY_MILL, BOUNDARY_E, MILL, line(512, 64, 0, 600, 64, 0)).profile(PROFILE_MAIN)
                .flags(RoadEdgeFlag.CLOSED).build()).build();
        RouteResult res = new AStarRouter(closedMill).routeOrExplain(RouteRequest.of(SnapPoint.atNode(closedMill, E),
            SnapPoint.atNode(closedMill, MILL), flags, params));
        assertEquals(RouteResult.Status.BLOCKED, res.status());
        assertEquals(EdgeVerdict.CauseType.FLAG, res.explanation().cause().type());
        assertEquals(List.of(E_E_BOUNDARY, E_STITCH), edges(res.route()));
    }

    @Test
    void compositePolicyCombinesGateAndDomainRules() {
        CompositeAccessPolicy policy = CompositeAccessPolicy.of(new StaticFlagsAvailability(),
            gate(AnimationState.CLOSED, true, true), castle(false, null, Set.of()));
        RouteResult toE = router.route(request(SnapPoint.atNode(town, A), SnapPoint.atNode(town, E), policy));
        assertTrue(toE.isFound());
        assertEquals(1, toE.route().passThroughSteps().size());
        RouteResult toCastle = router.routeOrExplain(request(SnapPoint.atNode(town, A), SnapPoint.atNode(town, CASTLE),
            policy));
        assertEquals(RouteResult.Status.BLOCKED, toCastle.status());
        assertTrue(toCastle.explanation().isDomainBlock());
    }

    // ---- route helpers ----------------------------------------------------------------------------

    @Test
    void routeProjectionAndPointsAlong() {
        Route route = router.route(request(SnapPoint.onEdge(town, E_AB, 30), SnapPoint.onEdge(town, E_BC, 40),
            AccessPolicy.ALL_OPEN)).route();
        assertEquals(110, route.polylineLength(), 1e-9);
        assertArrayEq(new double[] {100, 64, 20}, route.pointAt(90));
        assertArrayEq(new double[] {100, 64, 40}, route.pointAt(500));
        Route.Projection near = route.project(60, 64, 3, 0);
        assertEquals(30, near.along(), 1e-9);
        assertEquals(3, near.distance(), 1e-9);
        Route.Projection past = route.project(60, 64, 3, 80);
        assertEquals(73, past.along(), 1e-9, "segments before minAlong are skipped: nearest is on the second leg");
        assertEquals(40, past.distance(), 1e-9);
        Route.Projection off = route.project(100, 64, 30, 0);
        assertEquals(100, off.along(), 1e-9);
        assertEquals(0, off.distance(), 1e-9);
        Route empty = Route.empty(SnapPoint.onEdge(town, E_AB, 5));
        assertEquals(5, empty.project(0, 64, 0, 0).distance(), 1e-9);
        assertArrayEq(new double[] {5, 64, 0}, empty.pointAt(3));
    }

    @Test
    void truncatedRouteEndsWhereTheLastKeptStepExits() {
        Route full = router.route(request(TWENTY, CASTLE)).route(); // -18, -12, 17
        Route two = full.truncated(town, 2);
        assertEquals(List.of(-E_D20, -E_CD), edges(two));
        assertEquals(200, two.length(), 1e-9);
        assertEquals(100, two.end().x(), 1e-9);
        assertEquals(100, two.end().z(), 1e-9);
        assertEquals(List.of(D), two.nodeIds());
        assertEquals(90 + 100, two.cost(), 1e-9);
        assertTrue(full.truncated(town, 0).isEmpty());
        assertEquals(full, full.truncated(town, 5));
        assertEquals("Route{length=300.0, edges=[-18, -12, 17]}", full.toString());
    }

    private static void assertArrayEq(double[] expected, double[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], 1e-9, "index " + i);
        }
        assertFalse(actual.length == 0);
    }
}

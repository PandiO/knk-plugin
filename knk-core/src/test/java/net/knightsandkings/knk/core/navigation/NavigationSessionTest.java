package net.knightsandkings.knk.core.navigation;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.navigation.NavigationEffect.ArrivedEffect;
import net.knightsandkings.knk.core.navigation.NavigationEffect.BlockedEndReachedEffect;
import net.knightsandkings.knk.core.navigation.NavigationEffect.ComputeRouteEffect;
import net.knightsandkings.knk.core.navigation.NavigationEffect.EndReason;
import net.knightsandkings.knk.core.navigation.NavigationEffect.EndedEffect;
import net.knightsandkings.knk.core.navigation.NavigationEffect.GuidanceEffect;
import net.knightsandkings.knk.core.navigation.NavigationEffect.RerouteStartedEffect;
import net.knightsandkings.knk.core.navigation.NavigationEffect.RouteAdoptedEffect;
import net.knightsandkings.knk.core.navigation.NavigationEffect.RouteKeptEffect;
import net.knightsandkings.knk.core.navigation.NavigationEffect.RouteReason;
import net.knightsandkings.knk.core.roads.route.AStarRouter;
import net.knightsandkings.knk.core.roads.route.AccessPolicy;
import net.knightsandkings.knk.core.roads.route.EdgeVerdict;
import net.knightsandkings.knk.core.roads.route.GateAvailability;
import net.knightsandkings.knk.core.roads.route.ManeuverBuilder;
import net.knightsandkings.knk.core.roads.route.NetworkFixtureAccess;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;
import net.knightsandkings.knk.core.roads.route.Route;
import net.knightsandkings.knk.core.roads.route.RouteRequest;
import net.knightsandkings.knk.core.roads.route.RouteResult;
import net.knightsandkings.knk.core.roads.route.RouterParameters;
import net.knightsandkings.knk.core.roads.route.SnapPoint;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NavigationSessionTest {

    private final RoadNetworkSnapshot town = NetworkFixtureAccess.town();
    private final AStarRouter router = new AStarRouter(town);
    private final ManeuverBuilder maneuvers = new ManeuverBuilder(town);
    private final SessionParameters params = SessionParameters.defaults();

    private NavigationSession session(long tick) {
        return new NavigationSession(params, maneuvers::build, tick);
    }

    private NavigationSession session(SessionParameters p, long tick) {
        return new NavigationSession(p, maneuvers::build, tick);
    }

    /** A → E through B along Keepstreet (z = 0), gate on the last edge. */
    private RouteResult routeAtoE(AccessPolicy policy) {
        return router.routeOrExplain(RouteRequest.of(SnapPoint.atNode(town, NetworkFixtureAccess.A),
            SnapPoint.atNode(town, NetworkFixtureAccess.E), policy, RouterParameters.defaults()));
    }

    private static GateAvailability closedGate() {
        GateAvailability.GateView view = new GateAvailability.GateView(NetworkFixtureAccess.GATE_DOOR, "West Gate",
            AnimationState.CLOSED, false, false, false, false, false);
        return new GateAvailability(id -> Optional.of(view), id -> false);
    }

    private static <T> T only(List<NavigationEffect> effects, Class<T> type) {
        List<NavigationEffect> matching = effects.stream().filter(type::isInstance).toList();
        assertEquals(1, matching.size(), "expected exactly one " + type.getSimpleName() + " in " + effects);
        return type.cast(matching.get(0));
    }

    /** Guided session on the A → E route, player standing at A. */
    private NavigationSession guided(long tick) {
        NavigationSession s = session(tick);
        s.start();
        s.onRouteResult(routeAtoE(AccessPolicy.ALL_OPEN), tick);
        assertEquals(NavigationSession.State.GUIDING, s.state());
        return s;
    }

    @Test
    void startRequestsTheFirstRouteAndAdoptsIt() {
        NavigationSession s = session(0);
        assertEquals(NavigationSession.State.PLANNING, s.state());
        ComputeRouteEffect compute = only(s.start(), ComputeRouteEffect.class);
        assertEquals(RouteReason.INITIAL, compute.reason());
        assertFalse(compute.keepCurrentUnlessShorter());
        assertTrue(s.start().isEmpty(), "start is one-shot");
        List<NavigationEffect> effects = s.onRouteResult(routeAtoE(AccessPolicy.ALL_OPEN), 1);
        RouteAdoptedEffect adopted = only(effects, RouteAdoptedEffect.class);
        assertEquals(RouteReason.INITIAL, adopted.reason());
        assertEquals(200, adopted.route().length(), 1e-9);
        assertTrue(adopted.explanation().isEmpty());
        assertTrue(adopted.maneuvers().isEmpty(), "straight along Keepstreet");
        assertEquals(NavigationSession.State.GUIDING, s.state());
        assertTrue(s.isActive());
        assertEquals(200, s.remainingBlocks(), 1e-9);
    }

    @Test
    void noRouteOrDifferentComponentsEndTheSession() {
        NavigationSession s = session(0);
        s.start();
        assertEquals(EndReason.NO_ROUTE, only(s.onRouteResult(RouteResult.noRoute(), 1), EndedEffect.class).reason());
        assertEquals(NavigationSession.State.ENDED, s.state());
        assertEquals(EndReason.NO_ROUTE, s.endReason().orElseThrow());
        assertTrue(s.tick(0, 65, 0, 2).isEmpty(), "ended sessions ignore ticks");
        assertTrue(s.end(EndReason.STOPPED).isEmpty(), "end is idempotent");

        NavigationSession t = session(0);
        t.start();
        assertEquals(EndReason.DIFFERENT_COMPONENTS,
            only(t.onRouteResult(RouteResult.differentComponents(), 1), EndedEffect.class).reason());
    }

    @Test
    void guidanceReportsProgressRemainingEtaAndNextManeuver() {
        NavigationSession s = session(0);
        s.start();
        // A → C via B with paths made expensive: a right turn at B
        RouteResult r = router.route(new RouteRequest(SnapPoint.atNode(town, NetworkFixtureAccess.A),
            List.of(SnapPoint.atNode(town, NetworkFixtureAccess.C)), AccessPolicy.ALL_OPEN,
            java.util.Map.of(net.knightsandkings.knk.core.domain.roads.RoadClass.PATH, 1.5)));
        s.onRouteResult(r, 1);
        GuidanceEffect g = only(s.tick(40, 65, 0.5, 2), GuidanceEffect.class);
        assertEquals(40, g.along(), 1e-9);
        assertEquals(0.5, g.offRoute(), 1e-9);
        assertEquals(160, g.remainingBlocks(), 1e-9);
        assertEquals(160 / 5.6, g.etaSeconds(), 1e-9);
        assertEquals(0.2, g.progress(), 1e-9);
        assertEquals(46, g.aheadPoint()[0], 1e-9);
        assertTrue(g.nextManeuver().isPresent());
        assertEquals("Turn right onto Merchantstreet", g.nextManeuver().get().text());
        assertEquals(60, g.metersToNext(), 1e-9);
        // past the junction: no maneuver left
        GuidanceEffect later = only(s.tick(100, 65, 30, 3), GuidanceEffect.class);
        assertEquals(130, later.along(), 1e-9);
        assertTrue(later.nextManeuver().isEmpty());
        assertEquals(70, later.remainingBlocks(), 1e-9);
        // progress never runs backwards beyond the look-back
        GuidanceEffect back = only(s.tick(100, 65, 20, 4), GuidanceEffect.class);
        assertEquals(130, back.along(), 1e-9);
    }

    @Test
    void arrivalWithinArriveDistance() {
        NavigationSession s = guided(0);
        assertInstanceOf(GuidanceEffect.class, s.tick(190, 65, 0, 1).get(0));
        List<NavigationEffect> arrived = s.tick(197, 65, 0, 2);
        only(arrived, ArrivedEffect.class);
        assertEquals(NavigationSession.State.ARRIVED, s.state());
        assertFalse(s.isActive());
        assertTrue(s.tick(200, 65, 0, 3).isEmpty());
        assertTrue(s.onElementBlocked(EdgeVerdict.open(), 3).isEmpty());
    }

    @Test
    void emptyRouteArrivesImmediately() {
        NavigationSession s = session(0);
        s.start();
        SnapPoint at = SnapPoint.atNode(town, NetworkFixtureAccess.A);
        List<NavigationEffect> effects = s.onRouteResult(RouteResult.found(Route.empty(at)), 1);
        only(effects, RouteAdoptedEffect.class);
        only(effects, ArrivedEffect.class);
        assertEquals(NavigationSession.State.ARRIVED, s.state());
    }

    @Test
    void offRouteForLongEnoughRequestsARerouteOnceAndRateLimited() {
        SessionParameters p = params.withReroute(8, 3, 10);
        NavigationSession s = session(p, 0);
        s.start();
        s.onRouteResult(routeAtoE(AccessPolicy.ALL_OPEN), 0);
        long tick = 1;
        // 20 blocks beside the road: off-route; two ticks are not enough
        assertTrue(s.tick(50, 65, 20, tick++).stream().noneMatch(ComputeRouteEffect.class::isInstance));
        assertTrue(s.tick(50, 65, 20, tick++).stream().noneMatch(ComputeRouteEffect.class::isInstance));
        List<NavigationEffect> third = s.tick(50, 65, 20, tick++);
        RerouteStartedEffect started = only(third, RerouteStartedEffect.class);
        assertEquals(RouteReason.OFF_ROUTE, started.reason());
        assertTrue(started.verdict().isEmpty());
        assertEquals(RouteReason.OFF_ROUTE, only(third, ComputeRouteEffect.class).reason());
        only(third, GuidanceEffect.class);
        assertEquals(NavigationSession.State.REROUTING, s.state());
        // still rerouting: the old route keeps being drawn, no second request
        for (int i = 0; i < 5; i++) {
            List<NavigationEffect> e = s.tick(50, 65, 20, tick++);
            only(e, GuidanceEffect.class);
            assertTrue(e.stream().noneMatch(ComputeRouteEffect.class::isInstance));
        }
        // the new route arrives (say the same one) → guiding again
        RouteAdoptedEffect adopted = only(s.onRouteResult(routeAtoE(AccessPolicy.ALL_OPEN), tick), RouteAdoptedEffect.class);
        assertEquals(RouteReason.OFF_ROUTE, adopted.reason());
        assertEquals(NavigationSession.State.GUIDING, s.state());
        // still off route, but within the 10-tick rate limit of the last request (tick 3): no request until tick 13
        for (; tick < 13; tick++) {
            assertTrue(s.tick(50, 65, 20, tick).stream().noneMatch(ComputeRouteEffect.class::isInstance),
                "tick " + tick);
        }
        assertEquals(1, s.tick(50, 65, 20, tick).stream().filter(ComputeRouteEffect.class::isInstance).count());
        // back on the road resets the counter
        s.onRouteResult(routeAtoE(AccessPolicy.ALL_OPEN), 100);
        s.tick(50, 65, 20, 101);
        s.tick(50, 65, 20, 102);
        s.tick(50, 65, 0, 103);
        assertTrue(s.tick(50, 65, 20, 104).stream().noneMatch(ComputeRouteEffect.class::isInstance));
    }

    @Test
    void gateClosingMidRouteReroutesWithTheReasonAndAdoptsThePartialRoute() {
        NavigationSession s = guided(0);
        EdgeVerdict closed = EdgeVerdict.blocked("the West Gate is closed",
            EdgeVerdict.Cause.gate(NetworkFixtureAccess.GATE_DOOR, "the West Gate"));
        List<NavigationEffect> effects = s.onElementBlocked(closed, 5);
        RerouteStartedEffect started = only(effects, RerouteStartedEffect.class);
        assertEquals(RouteReason.ELEMENT_BLOCKED, started.reason());
        assertEquals(closed, started.verdict().orElseThrow());
        assertEquals(RouteReason.ELEMENT_BLOCKED, only(effects, ComputeRouteEffect.class).reason());
        assertEquals(NavigationSession.State.REROUTING, s.state());
        assertTrue(s.onElementBlocked(closed, 6).isEmpty(), "already rerouting");
        // the recomputation can only reach the gate: a partial route with the explanation
        RouteResult blocked = routeAtoE(closedGate());
        assertEquals(RouteResult.Status.BLOCKED, blocked.status());
        RouteAdoptedEffect adopted = only(s.onRouteResult(blocked, 7), RouteAdoptedEffect.class);
        assertTrue(adopted.explanation().isPresent());
        assertEquals("the West Gate is closed", adopted.explanation().get().reason());
        assertEquals(100, adopted.route().length(), 1e-9, "as far as B");
        assertEquals(NavigationSession.State.GUIDING, s.state());
        assertTrue(s.explanation().isPresent());
        // the gate end of the partial route is no arrival (live test 2026-10-08, N5): announced once, still guiding
        BlockedEndReachedEffect end = only(s.tick(98, 65, 0, 8), BlockedEndReachedEffect.class);
        assertEquals("the West Gate is closed", end.explanation().reason());
        assertEquals(NavigationSession.State.GUIDING, s.state());
        assertTrue(s.tick(98, 65, 0, 9).stream().noneMatch(e -> e instanceof BlockedEndReachedEffect || e instanceof ArrivedEffect));
    }

    @Test
    void aPartialRouteTakesOnlyAFullRouteAsImprovement() {
        NavigationSession s = guided(0);
        s.onElementBlocked(EdgeVerdict.blocked("the West Gate is closed",
            EdgeVerdict.Cause.gate(NetworkFixtureAccess.GATE_DOOR, "the West Gate")), 5);
        s.onRouteResult(routeAtoE(closedGate()), 7);
        assertTrue(s.explanation().isPresent());

        ComputeRouteEffect compute = only(s.onElementOpened(300), ComputeRouteEffect.class);
        assertFalse(compute.keepCurrentUnlessShorter());
        // N5: the gate is still closed - the partial route stays, no "shorter route" announced
        only(s.onRouteResult(routeAtoE(closedGate()), 301), RouteKeptEffect.class);
        assertEquals(NavigationSession.State.GUIDING, s.state());
        assertEquals(100, s.route().orElseThrow().length(), 1e-9);

        only(s.onElementOpened(600), ComputeRouteEffect.class);
        RouteAdoptedEffect reopened = only(s.onRouteResult(routeAtoE(AccessPolicy.ALL_OPEN), 601), RouteAdoptedEffect.class);
        assertEquals(RouteReason.REOPENED, reopened.reason());
        assertTrue(reopened.explanation().isEmpty());
        assertEquals(200, s.route().orElseThrow().length(), 1e-9);
    }

    @Test
    void aNetworkChangeTakesTheNewRouteAsItIsWithTheNewInstructions() {
        // rev. 7 Part A: the routing view's edge ids differ from the stored network's
        NavigationSession s = guided(0);
        List<Route> builtWith = new java.util.ArrayList<>();

        ComputeRouteEffect compute = only(s.onNetworkChanged(r -> {
            builtWith.add(r);
            return List.of();
        }, 10), ComputeRouteEffect.class);
        assertEquals(RouteReason.NETWORK_CHANGED, compute.reason());
        assertFalse(compute.keepCurrentUnlessShorter());

        RouteResult same = routeAtoE(AccessPolicy.ALL_OPEN); // not shorter: an improvement would keep the old one
        RouteAdoptedEffect adopted = only(s.onRouteResult(same, 11), RouteAdoptedEffect.class);
        assertEquals(RouteReason.NETWORK_CHANGED, adopted.reason());
        assertEquals(same.route(), s.route().orElseThrow());
        assertEquals(List.of(same.route()), builtWith, "instructions from the new network's builder");

        s.onNetworkChanged(maneuvers::build, 20);
        RouteAdoptedEffect blocked = only(s.onRouteResult(routeAtoE(closedGate()), 21), RouteAdoptedEffect.class);
        assertEquals(RouteReason.ELEMENT_BLOCKED, blocked.reason(), "the new network blocks the way: announced");
        s.onNetworkChanged(maneuvers::build, 30);
        RouteAdoptedEffect open = only(s.onRouteResult(routeAtoE(AccessPolicy.ALL_OPEN), 31), RouteAdoptedEffect.class);
        assertEquals(RouteReason.REOPENED, open.reason());
    }

    @Test
    void elementOpenedAdoptsOnlyAClearlyShorterRoute() {
        NavigationSession s = guided(0);
        ComputeRouteEffect compute = only(s.onElementOpened(10), ComputeRouteEffect.class);
        assertEquals(RouteReason.IMPROVEMENT, compute.reason());
        assertTrue(compute.keepCurrentUnlessShorter());
        assertEquals(NavigationSession.State.REROUTING, s.state());
        // the same 200-block route again: kept
        only(s.onRouteResult(routeAtoE(AccessPolicy.ALL_OPEN), 11), RouteKeptEffect.class);
        assertEquals(NavigationSession.State.GUIDING, s.state());
        assertEquals(200, s.route().orElseThrow().length(), 1e-9);
        // rate limit: nothing for 200 ticks
        assertTrue(s.onElementOpened(100).isEmpty());
        assertFalse(s.onElementOpened(210).isEmpty());
        // a route 20 % shorter than what is left is adopted
        Route shorter = router.route(RouteRequest.of(SnapPoint.atNode(town, NetworkFixtureAccess.A),
            SnapPoint.onEdge(town, NetworkFixtureAccess.E_AB, 60), AccessPolicy.ALL_OPEN, RouterParameters.defaults()))
            .route();
        RouteAdoptedEffect adopted = only(s.onRouteResult(RouteResult.found(shorter), 211), RouteAdoptedEffect.class);
        assertEquals(RouteReason.IMPROVEMENT, adopted.reason());
        assertEquals(60, s.route().orElseThrow().length(), 1e-9);
        // a partial route is never an improvement
        s.onElementOpened(500);
        only(s.onRouteResult(routeAtoE(closedGate()), 501), RouteKeptEffect.class);
        // and a failed improvement keeps the route too
        s.onElementOpened(800);
        only(s.onRouteResult(RouteResult.noRoute(), 801), RouteKeptEffect.class);
        assertEquals(NavigationSession.State.GUIDING, s.state());
    }

    @Test
    void partialRouteRetriesWithoutTheShorterCondition() {
        NavigationSession s = session(0);
        s.start();
        s.onRouteResult(routeAtoE(closedGate()), 0);
        assertTrue(s.explanation().isPresent());
        ComputeRouteEffect compute = only(s.onElementOpened(300), ComputeRouteEffect.class);
        assertFalse(compute.keepCurrentUnlessShorter(), "the blocking gate may have opened: take any full route");
        RouteAdoptedEffect adopted = only(s.onRouteResult(routeAtoE(AccessPolicy.ALL_OPEN), 301), RouteAdoptedEffect.class);
        assertTrue(adopted.explanation().isEmpty());
        assertEquals(200, adopted.route().length(), 1e-9);
    }

    @Test
    void sessionTimesOutAndRuntimeReasonsEndIt() {
        NavigationSession s = guided(1000);
        long limit = params.maxSessionTicks();
        only(s.tick(10, 65, 0, 1000 + limit - 1), GuidanceEffect.class);
        assertEquals(EndReason.TIMEOUT, only(s.tick(10, 65, 0, 1000 + limit), EndedEffect.class).reason());
        NavigationSession t = guided(0);
        assertEquals(EndReason.SIEGE, only(t.end(EndReason.SIEGE), EndedEffect.class).reason());
        assertTrue(t.onRouteResult(routeAtoE(AccessPolicy.ALL_OPEN), 1).isEmpty(), "late results are dropped");
    }

    @Test
    void parametersValidateAndConvert() {
        assertEquals(36_000, params.maxSessionTicks());
        assertEquals(8, params.rerouteDistance());
        assertEquals(40, params.rerouteAfterTicks());
        assertEquals(60, params.rerouteMinIntervalTicks());
        assertEquals(200, params.improvementIntervalTicks());
        assertEquals(0.15, params.improvementThreshold());
        assertEquals(4, params.arriveDistance());
        assertEquals(30, params.maxSessionMinutes());
        assertEquals(5.6, params.sprintSpeed());
        assertEquals(5, params.withArriveDistance(5).arriveDistance());
        assertEquals(1200, params.withMaxSessionMinutes(1).maxSessionTicks());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> params.withReroute(0, 1, 1));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> params.withMaxSessionMinutes(0));
    }
}

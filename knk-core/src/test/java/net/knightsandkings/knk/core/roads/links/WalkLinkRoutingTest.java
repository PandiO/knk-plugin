package net.knightsandkings.knk.core.roads.links;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.roads.route.AStarRouter;
import net.knightsandkings.knk.core.roads.route.AccessPolicy;
import net.knightsandkings.knk.core.roads.route.CompositeAccessPolicy;
import net.knightsandkings.knk.core.roads.route.EdgeVerdict;
import net.knightsandkings.knk.core.roads.route.Maneuver;
import net.knightsandkings.knk.core.roads.route.ManeuverBuilder;
import net.knightsandkings.knk.core.roads.route.Route;
import net.knightsandkings.knk.core.roads.route.RouteRequest;
import net.knightsandkings.knk.core.roads.route.RouteResult;
import net.knightsandkings.knk.core.roads.route.WalkLink;
import net.knightsandkings.knk.core.roads.route.WalkLinkId;
import net.knightsandkings.knk.core.roads.route.WalkLinkSource;

/**
 * Off-road phase 2 (#705): the road A* with walk links (DESIGN § 5.1, § 5.2; A02 to A05) and the flag-off identity
 * (with no link source the router is today's).
 */
class WalkLinkRoutingTest {

    private static final String MOVER = "player-1";

    private final CourtyardNetwork net = new CourtyardNetwork();
    private final AtomicLong clock = new AtomicLong(0);
    private final WalkLinkCache cache = new WalkLinkCache(clock::get, 600, 600, 100);
    private final WalkLinkCandidates candidates = new WalkLinkCandidates(net.snapshot, 40, 2.0);
    private final AStarRouter router = new AStarRouter(net.snapshot);

    private WalkLinkPlanner planner(AccessPolicy policy) {
        return new WalkLinkPlanner(candidates, cache, CourtyardNetwork.WORLD, MOVER, policy,
            net.snapshot.minCostFactor(CourtyardNetwork.classCost()));
    }

    private WalkLinkCache.Key key(int from, int to) {
        return new WalkLinkCache.Key(CourtyardNetwork.WORLD, MOVER, WalkLinkId.of(from, to));
    }

    /** Everything a player is shown of a route: its edges, lengths, cost, polyline and instructions. */
    private static String transcript(RouteResult result) {
        if (result.route() == null) {
            return result.status().toString();
        }
        Route r = result.route();
        StringBuilder sb = new StringBuilder(result.status() + " " + r + " " + r.length() + " " + r.cost() + " "
            + r.polylineLength() + " " + r.nodeIds());
        r.polyline().forEach(p -> sb.append(' ').append(Arrays.toString(p)));
        r.steps().forEach(s -> sb.append(" step ").append(s.edge().id()).append(' ').append(s.startDistance()));
        return sb.toString();
    }

    @Test
    void withoutALinkSourceTheRouterIsTodays() {
        // flag off: route(request) and route(request, null) are one code path; NONE (no links anywhere) as well
        RouteRequest request = net.marketToKeep(AccessPolicy.ALL_OPEN);
        String today = transcript(router.route(request));
        assertEquals(today, transcript(router.route(request, null)));
        assertEquals(today, transcript(router.route(request, WalkLinkSource.NONE)));
        assertEquals(today, transcript(router.routeOrExplain(request, null)));
        assertEquals(200, router.route(request).route().length(), 1e-9, "round the courtyard");
        assertFalse(router.route(request).route().hasWalkLinks());
    }

    @Test
    void anUnsearchedCandidateIsWantedAndTheRouteStaysOnTheRoad() {
        // DESIGN § 5.2.2, lazy: the A* reaches A, the link A -> B is unknown, the route is today's, the link is wanted
        WalkLinkPlanner planner = planner(AccessPolicy.ALL_OPEN);
        RouteResult result = router.route(net.marketToKeep(AccessPolicy.ALL_OPEN), planner);
        assertEquals(transcript(router.route(net.marketToKeep(AccessPolicy.ALL_OPEN))), transcript(result));
        List<WalkLinkPlanner.Wanted> wanted = planner.wanted(4, result.route().cost());
        assertEquals(List.of(WalkLinkId.of(CourtyardNetwork.A, CourtyardNetwork.B)),
            wanted.stream().map(w -> w.candidate().id()).toList(), "only the link that could beat 200");
        assertTrue(wanted.get(0).lowerBound() < 200);
        assertTrue(planner.wanted(0, 200).isEmpty(), "the limit");
    }

    @Test
    void aCachedLinkMakesTheRouteRoadWalkRoad() {
        // A02 (core part): road S -> A, the link across the courtyard, road B -> T; the runtime adds the walk legs at
        // both ends (walk, road, walk, road, walk)
        WalkLink link = CourtyardNetwork.courtyardLink(List.of(), List.of());
        cache.putFound(key(CourtyardNetwork.A, CourtyardNetwork.B), link);
        WalkLinkPlanner planner = planner(AccessPolicy.ALL_OPEN);
        Route route = router.route(net.marketToKeep(AccessPolicy.ALL_OPEN), planner).route();

        assertTrue(route.hasWalkLinks(), route.toString());
        assertEquals(60 + 22 + 60, route.length(), 1e-9);
        assertEquals(List.of(CourtyardNetwork.E_SA, CourtyardNetwork.E_BT),
            route.steps().stream().map(s -> s.edge().id()).toList());
        List<Route.Leg> legs = route.legs();
        assertEquals(3, legs.size());
        assertInstanceOf(Route.RoadLeg.class, legs.get(0));
        assertInstanceOf(Route.WalkLinkLeg.class, legs.get(1));
        assertInstanceOf(Route.RoadLeg.class, legs.get(2));
        assertEquals(60, legs.get(1).polylineFrom(), 1e-9);
        assertEquals(legs.get(1).polylineTo(), legs.get(2).polylineFrom(), 1e-9, "the legs follow each other");
        assertEquals(route.polylineLength(), legs.get(2).polylineTo(), 1e-9);
        assertEquals(60 + 22, route.steps().get(1).startDistance(), 1e-9, "the link's length counts");
        assertEquals(List.of(CourtyardNetwork.A, CourtyardNetwork.B), route.nodeIds());
        assertTrue(route.walkLinkAt(70).isPresent());
        assertTrue(route.walkLinkAt(30).isEmpty());
        double[] mid = route.pointAt(70);
        assertEquals(64, mid[1], 1e-9);
        assertTrue(mid[2] >= 0 && mid[2] <= 1 && mid[0] > 5, "across the courtyard, not round it: " + Arrays.toString(mid));
        assertEquals(1, planner.offeredCount());
        assertTrue(route.toString().contains("~link 2->5"), route.toString());
    }

    @Test
    void aLinkIsTakenOnlyWhereItBeatsTheRoad() {
        // a link that walks 90 blocks (longer than the road's 80) is offered but not taken
        WalkLink detour = WalkLink.ofWalkPath(WalkLinkId.of(CourtyardNetwork.A, CourtyardNetwork.B), CourtyardNetwork.WORLD,
            new double[] {0, 64, 0}, new double[] {20, 64, 0}, List.of(new double[] {10.5, 64, 20.5}), 90, 90, List.of(),
            List.of());
        cache.putFound(key(CourtyardNetwork.A, CourtyardNetwork.B), detour);
        Route route = router.route(net.marketToKeep(AccessPolicy.ALL_OPEN), planner(AccessPolicy.ALL_OPEN)).route();
        assertFalse(route.hasWalkLinks());
        assertEquals(200, route.length(), 1e-9);
    }

    @Test
    void aLinkThroughARegionThePlayerMayNotEnterOrAClosedGateIsNotOffered() {
        // A04: the policy judges the link's tags like a road edge's
        AccessPolicy deniesCourtyard = edge -> edge.regionIds().contains(CourtyardNetwork.COURTYARD)
            ? EdgeVerdict.blocked("You may not enter the courtyard.", EdgeVerdict.Cause.domain(1, "Courtyard"))
            : EdgeVerdict.open();
        AccessPolicy gateClosed = edge -> edge.gateDoorIds().contains(CourtyardNetwork.COURTYARD_DOOR)
            ? EdgeVerdict.blocked("The courtyard gate is closed.", EdgeVerdict.Cause.gate(CourtyardNetwork.COURTYARD_DOOR, "Gate"))
            : EdgeVerdict.open();

        cache.putFound(key(CourtyardNetwork.A, CourtyardNetwork.B),
            CourtyardNetwork.courtyardLink(List.of(CourtyardNetwork.COURTYARD), List.of()));
        WalkLinkPlanner denied = planner(CompositeAccessPolicy.of(deniesCourtyard));
        Route route = router.route(net.marketToKeep(CompositeAccessPolicy.of(deniesCourtyard)), denied).route();
        assertFalse(route.hasWalkLinks(), "a region the player may not enter");
        assertEquals(0, denied.offeredCount());
        assertTrue(denied.wanted(4, Double.POSITIVE_INFINITY).isEmpty(), "known, so not searched again");

        cache.putFound(key(CourtyardNetwork.A, CourtyardNetwork.B),
            CourtyardNetwork.courtyardLink(List.of(), List.of(CourtyardNetwork.COURTYARD_DOOR)));
        assertFalse(router.route(net.marketToKeep(gateClosed), planner(gateClosed)).route().hasWalkLinks(),
            "a closed gate");
        assertTrue(router.route(net.marketToKeep(AccessPolicy.ALL_OPEN), planner(AccessPolicy.ALL_OPEN)).route()
            .hasWalkLinks(), "the same link, gate open");
    }

    @Test
    void aCompositePolicyDoesNotMixALinkUpWithAnEdge() {
        // every link's probe edge has the same id: the composite must not answer a link from its per-edge cache
        List<String> asked = new ArrayList<>();
        AccessPolicy recording = edge -> {
            asked.add(edge.id() + " " + edge.regionIds());
            return edge.regionIds().contains("denied") ? EdgeVerdict.blocked("no", EdgeVerdict.Cause.domain(2, "Denied")) : EdgeVerdict.open();
        };
        CompositeAccessPolicy policy = CompositeAccessPolicy.of(recording);
        WalkLink open = CourtyardNetwork.courtyardLink(List.of(), List.of());
        WalkLink shut = CourtyardNetwork.courtyardLink(List.of("denied"), List.of());
        assertTrue(policy.checkWalkLink(open).isUsable());
        assertTrue(policy.checkWalkLink(shut).isBlocked());
        assertTrue(policy.checkWalkLink(open).isUsable());
        assertEquals(3, asked.size(), "asked every time");
    }

    @Test
    void aBlockChangeDropsTheLinkAndTheNextRouteSearchesAgain() {
        // A05
        cache.putFound(key(CourtyardNetwork.A, CourtyardNetwork.B), CourtyardNetwork.courtyardLink(List.of(), List.of()));
        assertTrue(router.route(net.marketToKeep(AccessPolicy.ALL_OPEN), planner(AccessPolicy.ALL_OPEN)).route()
            .hasWalkLinks());
        cache.terrainChanged(CourtyardNetwork.WORLD, 0, 0);
        WalkLinkPlanner again = planner(AccessPolicy.ALL_OPEN);
        RouteResult after = router.route(net.marketToKeep(AccessPolicy.ALL_OPEN), again);
        assertFalse(after.route().hasWalkLinks());
        assertEquals(1, again.wanted(4, after.route().cost()).size(), "wanted again");
    }

    @Test
    void aSearchThatFoundNoWayIsNotAskedAgainUntilItsRetryTime() {
        cache.putNone(key(CourtyardNetwork.A, CourtyardNetwork.B));
        WalkLinkPlanner planner = planner(AccessPolicy.ALL_OPEN);
        RouteResult result = router.route(net.marketToKeep(AccessPolicy.ALL_OPEN), planner);
        assertTrue(planner.wanted(4, result.route().cost()).isEmpty());
        clock.addAndGet(600_000);
        WalkLinkPlanner later = planner(AccessPolicy.ALL_OPEN);
        RouteResult retried = router.route(net.marketToKeep(AccessPolicy.ALL_OPEN), later);
        assertEquals(1, later.wanted(4, retried.route().cost()).size());
    }

    @Test
    void aLinkIsForTheMoverItWasSearchedFor() {
        cache.putFound(key(CourtyardNetwork.A, CourtyardNetwork.B), CourtyardNetwork.courtyardLink(List.of(), List.of()));
        WalkLinkPlanner someoneElse = new WalkLinkPlanner(candidates, cache, CourtyardNetwork.WORLD, "player-2",
            AccessPolicy.ALL_OPEN, 1);
        assertFalse(router.route(net.marketToKeep(AccessPolicy.ALL_OPEN), someoneElse).route().hasWalkLinks(),
            "searched with player-1's cell access (doors, gates, regions)");
    }

    @Test
    void theRouteWithALinkKeepsItThroughVerdictsAndTruncation() {
        cache.putFound(key(CourtyardNetwork.A, CourtyardNetwork.B), CourtyardNetwork.courtyardLink(List.of(), List.of()));
        Route route = router.route(net.marketToKeep(AccessPolicy.ALL_OPEN), planner(AccessPolicy.ALL_OPEN)).route();
        Route again = route.withVerdicts(net.snapshot, AccessPolicy.ALL_OPEN);
        assertEquals(route.toString(), again.toString());
        assertEquals(route.length(), again.length(), 1e-9);
        Route head = route.truncated(net.snapshot, 1);
        assertFalse(head.hasWalkLinks(), "the link lies after the first step");
        assertEquals(60, head.length(), 1e-9);
    }

    @Test
    void theInstructionsSayWhereTheRouteLeavesTheRoad() {
        cache.putFound(key(CourtyardNetwork.A, CourtyardNetwork.B), CourtyardNetwork.courtyardLink(List.of(), List.of()));
        Route route = router.route(net.marketToKeep(AccessPolicy.ALL_OPEN), planner(AccessPolicy.ALL_OPEN)).route();
        List<Maneuver> maneuvers = new ManeuverBuilder(net.snapshot).build(route);
        assertEquals(1, maneuvers.size(), maneuvers.toString());
        assertEquals(Maneuver.Kind.WALK, maneuvers.get(0).kind());
        assertEquals(ManeuverBuilder.WALK_TEXT, maneuvers.get(0).text());
        assertEquals(60, maneuvers.get(0).along(), 1e-9);
        assertFalse(maneuvers.get(0).kind().isTurn());
    }

    @Test
    void theRoutingCostOfALinkNeverUndercutsTheHeuristic() {
        // with every class cost at 2, a link costs its walk cost times 2 (never cheaper per block than the cheapest road)
        WalkLink link = CourtyardNetwork.courtyardLink(List.of(), List.of());
        cache.putFound(key(CourtyardNetwork.A, CourtyardNetwork.B), link);
        WalkLinkPlanner planner = new WalkLinkPlanner(candidates, cache, CourtyardNetwork.WORLD, MOVER,
            AccessPolicy.ALL_OPEN, 2.0);
        WalkLinkSource.Links links = planner.at(CourtyardNetwork.A);
        assertEquals(link.walkCost() * 2, links.usable().get(0).routingCost(), 1e-9);
        assertEquals(link.walkCost(), new WalkLinkPlanner(candidates, cache, CourtyardNetwork.WORLD, MOVER,
            AccessPolicy.ALL_OPEN, 0.5).at(CourtyardNetwork.A).usable().get(0).routingCost(), 1e-9, "at least 1 x");
    }
}

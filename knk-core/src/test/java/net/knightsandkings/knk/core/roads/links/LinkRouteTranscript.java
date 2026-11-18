package net.knightsandkings.knk.core.roads.links;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

import net.knightsandkings.knk.core.roads.route.AStarRouter;
import net.knightsandkings.knk.core.roads.route.AccessPolicy;
import net.knightsandkings.knk.core.roads.route.Route;
import net.knightsandkings.knk.core.roads.route.RouteRequest;
import net.knightsandkings.knk.core.roads.route.RouteResult;
import net.knightsandkings.knk.core.roads.route.RouterParameters;
import net.knightsandkings.knk.core.roads.route.SnapPoint;
import net.knightsandkings.knk.core.roads.route.WalkLink;
import net.knightsandkings.knk.core.roads.route.WalkLinkId;

/**
 * Off-road phase 4 (PandiO/knk-workspace#707, O24): today's routing with walk links off and on, as text. Uses only the
 * API from before phase 4, so the same file builds the golden transcript on the base branch
 * ({@code src/test/resources/route-modes/links-default.txt}); the test hooks the mode in.
 */
final class LinkRouteTranscript {

    private static final String MOVER = "player-1";

    /** The planner the test builds (today's constructor on the base branch). */
    interface Planners {
        WalkLinkPlanner make(WalkLinkCandidates candidates, WalkLinkCache cache, String world, String mover,
                             AccessPolicy policy, double minFactor);
    }

    private LinkRouteTranscript() {
    }

    static String transcript(UnaryOperator<RouteRequest> hook, Planners planners) {
        CourtyardNetwork net = new CourtyardNetwork();
        AStarRouter router = new AStarRouter(net.snapshot);
        double minFactor = net.snapshot.minCostFactor(CourtyardNetwork.classCost());
        WalkLinkCandidates candidates = new WalkLinkCandidates(net.snapshot, 40, 2.0);
        StringBuilder sb = new StringBuilder();
        List<Object[]> links = List.of(
            new Object[] {"no-link", null},
            new Object[] {"courtyard", CourtyardNetwork.courtyardLink(List.of(), List.of())},
            new Object[] {"detour", WalkLink.ofWalkPath(WalkLinkId.of(CourtyardNetwork.A, CourtyardNetwork.B),
                CourtyardNetwork.WORLD, new double[] {0, 64, 0}, new double[] {20, 64, 0},
                List.of(new double[] {10.5, 64, 20.5}), 90, 90, List.of(), List.of())},
            new Object[] {"slow", WalkLink.ofWalkPath(WalkLinkId.of(CourtyardNetwork.A, CourtyardNetwork.B),
                CourtyardNetwork.WORLD, new double[] {0, 64, 0}, new double[] {20, 64, 0},
                List.of(new double[] {5.5, 64, 0.5}, new double[] {15.5, 64, 0.5}), 22, 70, List.of(), List.of())});
        int[] ends = {CourtyardNetwork.S, CourtyardNetwork.A, CourtyardNetwork.B, CourtyardNetwork.T};
        for (Object[] link : links) {
            for (int from : ends) {
                for (int to : ends) {
                    if (from == to) {
                        continue;
                    }
                    WalkLinkCache cache = new WalkLinkCache(new AtomicLong(0)::get, 600, 600, 100);
                    if (link[1] != null) {
                        cache.putFound(new WalkLinkCache.Key(CourtyardNetwork.WORLD, MOVER,
                            WalkLinkId.of(CourtyardNetwork.A, CourtyardNetwork.B)), (WalkLink) link[1]);
                    }
                    RouteRequest request = hook.apply(RouteRequest.of(SnapPoint.atNode(net.snapshot, from),
                        SnapPoint.atNode(net.snapshot, to), AccessPolicy.ALL_OPEN, RouterParameters.defaults()));
                    sb.append(link[0]).append(' ').append(from).append(" -> ").append(to).append(" off: ")
                        .append(result(router.routeOrExplain(request))).append('\n');
                    WalkLinkPlanner planner = planners.make(candidates, cache, CourtyardNetwork.WORLD, MOVER,
                        AccessPolicy.ALL_OPEN, minFactor);
                    RouteResult on = router.routeOrExplain(request, planner);
                    sb.append(link[0]).append(' ').append(from).append(" -> ").append(to).append(" on: ")
                        .append(result(on)).append(" offered=").append(planner.offeredCount());
                    double cost = on.route() == null ? Double.POSITIVE_INFINITY : on.route().cost();
                    for (WalkLinkPlanner.Wanted w : planner.wanted(4, cost)) {
                        sb.append(" wanted ").append(w.candidate().id()).append('@').append(w.lowerBound());
                    }
                    sb.append('\n');
                }
            }
        }
        return sb.toString();
    }

    static String result(RouteResult result) {
        StringBuilder sb = new StringBuilder(result.status().toString());
        Route r = result.route();
        if (r == null) {
            return sb.toString();
        }
        sb.append(' ').append(r).append(" length=").append(r.length()).append(" cost=").append(r.cost())
            .append(" polyline=").append(r.polylineLength()).append(" nodes=").append(r.nodeIds())
            .append(" links=").append(r.hasWalkLinks());
        r.steps().forEach(s -> sb.append(" step ").append(s.edge().id()).append(' ').append(s.cost()).append(' ')
            .append(s.startDistance()));
        r.polyline().forEach(p -> sb.append(' ').append(Arrays.toString(p)));
        return sb.toString();
    }
}

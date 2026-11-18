package net.knightsandkings.knk.core.roads.route;

import net.knightsandkings.knk.core.domain.roads.RoadClass;
import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import static net.knightsandkings.knk.core.roads.route.NetworkFixture.edge;
import static net.knightsandkings.knk.core.roads.route.NetworkFixture.line;
import static net.knightsandkings.knk.core.roads.route.NetworkFixture.node;
import static net.knightsandkings.knk.core.roads.route.NetworkFixture.p;

/**
 * Off-road phase 4 (PandiO/knk-workspace#707): the road scenes of the route mode tests and the transcript of today's
 * road routing (O24). Uses only the router's API from before phase 4, so the same file builds the golden transcript on
 * the base branch ({@code src/test/resources/route-modes/road-default.txt}); the test hooks the mode in.
 */
final class RouteModeScenes {

    static final int S = 1, T = 2, M_SHORT = 3, M_MAIN = 4, M_ROAD = 5;
    static final int E_S1 = 31, E_1T = 32, E_S2 = 33, E_2T = 34, E_S3 = 35, E_3T = 36;
    static final int P_LANE = 1, P_MAIN = 2, P_ROAD = 3;

    private RouteModeScenes() {
    }

    /**
     * From S (0, 64, 0) to T (100, 64, 0), three ways:
     * <ol>
     *     <li>the lane over M_SHORT: 100 blocks of Path (class cost 1.15) on a profile with multiplier
     *     {@code laneProfileMultiplier};</li>
     *     <li>the main road over M_MAIN: 110 blocks of Main (0.9) with an edge cost multiplier {@code mainEdgeMultiplier}
     *     (a staff weight);</li>
     *     <li>the town road over M_ROAD: 120 blocks of Road (1.0), no multipliers.</li>
     * </ol>
     * With 1.5 and 2.0: shortest 100 / 110 / 120 (the lane), efficient 115 / 99 / 120 (the main road), preferred
     * 172.5 / 198 / 120 (the town road).
     */
    static RoadNetworkSnapshot threeWays(double laneProfileMultiplier, double mainEdgeMultiplier) {
        RoadNetworkSnapshot.Builder b = RoadNetworkSnapshot.builder("world")
            .addProfile(new RoadNetworkSnapshot.Profile(P_LANE, "lane", RoadClass.PATH, laneProfileMultiplier))
            .addProfile(new RoadNetworkSnapshot.Profile(P_MAIN, "main", RoadClass.MAIN, 1.0))
            .addProfile(new RoadNetworkSnapshot.Profile(P_ROAD, "road", RoadClass.ROAD, 1.0));
        b.addNode(node(S, 0, 64, 0, RoadNodeKind.JUNCTION, "S", 1));
        b.addNode(node(T, 100, 64, 0, RoadNodeKind.JUNCTION, "T", 1));
        b.addNode(node(M_SHORT, 50, 64, 0, RoadNodeKind.JUNCTION, null, 1));
        b.addNode(node(M_MAIN, 50, 64, 20, RoadNodeKind.JUNCTION, null, 1));
        b.addNode(node(M_ROAD, 50, 64, -30, RoadNodeKind.JUNCTION, null, 1));
        b.addEdge(edge(E_S1, S, M_SHORT, line(0, 64, 0, 50, 64, 0)).profile(P_LANE).build());
        b.addEdge(edge(E_1T, M_SHORT, T, line(50, 64, 0, 100, 64, 0)).profile(P_LANE).build());
        b.addEdge(edge(E_S2, S, M_MAIN, List.of(p(0, 64, 0), p(50, 64, 20))).length(55).profile(P_MAIN)
            .cost(mainEdgeMultiplier).build());
        b.addEdge(edge(E_2T, M_MAIN, T, List.of(p(50, 64, 20), p(100, 64, 0))).length(55).profile(P_MAIN)
            .cost(mainEdgeMultiplier).build());
        b.addEdge(edge(E_S3, S, M_ROAD, List.of(p(0, 64, 0), p(50, 64, -30))).length(60).profile(P_ROAD).build());
        b.addEdge(edge(E_3T, M_ROAD, T, List.of(p(50, 64, -30), p(100, 64, 0))).length(60).profile(P_ROAD).build());
        return b.build();
    }

    /** The town with a staff weight on the diagonal and a dearer road profile: every multiplier of § 6.2 in use. */
    static RoadNetworkSnapshot weightedTown() {
        return NetworkFixture.townBuilder()
            .addProfile(new RoadNetworkSnapshot.Profile(NetworkFixture.PROFILE_ROAD, "town road", RoadClass.ROAD, 1.3))
            .addEdge(edge(NetworkFixture.E_AC, NetworkFixture.A, NetworkFixture.C,
                List.of(p(0, 64, 0), p(50, 64, 50), p(100, 64, 100))).length(150)
                .profile(NetworkFixture.PROFILE_PATH).cost(1.7).build())
            .addEdge(edge(NetworkFixture.E_D20, NetworkFixture.D, NetworkFixture.TWENTY, line(0, 64, 100, 0, 64, 200))
                .profile(NetworkFixture.PROFILE_MAIN).cost(0.6).build())
            .build();
    }

    /** A gate policy: the West Gate's edge is shut, so the explainer answers (BLOCKED with a partial route). */
    static AccessPolicy westGateShut() {
        return e -> e.gateDoorIds().contains(NetworkFixture.GATE_DOOR)
            ? EdgeVerdict.blocked("The West Gate is closed.", EdgeVerdict.Cause.gate(NetworkFixture.GATE_DOOR, "West Gate"))
            : EdgeVerdict.open();
    }

    /**
     * Today's routing as text: for each scene, every ordered pair of nodes and a few points in the middle of edges,
     * with every policy, the status, the edges, length, cost (full precision), nodes, polyline and the explainer's
     * partial route. {@code hook} turns the plain request into the request under test (identity on the base branch).
     */
    static String transcript(UnaryOperator<RouteRequest> hook) {
        StringBuilder sb = new StringBuilder();
        List<Object[]> scenes = List.of(
            new Object[] {"town", NetworkFixture.town()},
            new Object[] {"weighted-town", weightedTown()},
            new Object[] {"three-ways", threeWays(1.5, 2.0)},
            new Object[] {"three-ways-plain", threeWays(1.0, 1.0)},
            new Object[] {"three-ways-cheap", threeWays(1.5, 0.5)});
        RouterParameters params = RouterParameters.defaults();
        for (Object[] scene : scenes) {
            String name = (String) scene[0];
            RoadNetworkSnapshot net = (RoadNetworkSnapshot) scene[1];
            AStarRouter router = new AStarRouter(net);
            List<SnapPoint> nodes = new ArrayList<>();
            for (RoadNode n : net.nodes()) {
                nodes.add(SnapPoint.atNode(net, n.id()));
            }
            List<SnapPoint> withMiddles = new ArrayList<>(nodes);
            for (RoadEdge e : net.edges().subList(0, Math.min(3, net.edges().size()))) {
                withMiddles.add(SnapPoint.onEdge(net, e.id(), net.polyline(e).length() / 3));
            }
            boolean gated = net.edges().stream().anyMatch(e -> e.gateDoorIds().contains(NetworkFixture.GATE_DOOR));
            for (String policyName : gated ? List.of("open", "gate-shut") : List.of("open")) {
                AccessPolicy policy = policyName.equals("open") ? AccessPolicy.ALL_OPEN : westGateShut();
                List<SnapPoint> points = policyName.equals("open") ? withMiddles : nodes;
                for (SnapPoint from : points) {
                    for (SnapPoint to : points) {
                        if (from == to) {
                            continue;
                        }
                        sb.append(name).append(' ').append(policyName).append(' ').append(point(from)).append(" -> ")
                            .append(point(to)).append(": ");
                        try {
                            RouteRequest request = hook.apply(RouteRequest.of(from, to, policy, params));
                            sb.append(result(router.routeOrExplain(request)));
                        } catch (RuntimeException ex) {
                            sb.append("threw ").append(ex.getClass().getSimpleName());
                        }
                        sb.append('\n');
                    }
                }
                // the multi-goal request (a region's goal set): the start of every edge as a goal from node A or S
                SnapPoint from = points.get(0);
                List<SnapPoint> goals = points.subList(Math.max(1, points.size() - 4), points.size());
                RouteRequest multi = hook.apply(RouteRequest.of(from, goals, policy, params));
                sb.append(name).append(' ').append(policyName).append(" multi: ").append(result(router.routeOrExplain(multi)))
                    .append('\n');
            }
        }
        return sb.toString();
    }

    static String point(SnapPoint p) {
        return "e" + p.edgeId() + "@" + p.along();
    }

    static String result(RouteResult result) {
        StringBuilder sb = new StringBuilder(result.status().toString());
        if (result.route() != null) {
            sb.append(' ').append(route(result.route()));
        }
        result.explanationOptional().ifPresent(x -> sb.append(" blocked-at ").append(x.blockedEdge().id())
            .append(" '").append(x.verdict().message()).append("' partial ").append(route(x.partialRoute()))
            .append(" full ").append(route(x.fullRoute())));
        return sb.toString();
    }

    static String route(Route r) {
        StringBuilder sb = new StringBuilder("length=").append(r.length()).append(" cost=")
            .append(r.cost()).append(" nodes=").append(r.nodeIds());
        for (Route.Step s : r.steps()) {
            sb.append(" [").append(s.forward() ? "" : "-").append(s.edge().id()).append(' ').append(s.entryAlong())
                .append('>').append(s.exitAlong()).append(" len=").append(s.length()).append(" cost=").append(s.cost())
                .append(" at=").append(s.startDistance()).append(']');
        }
        // the steps fix the polyline; its size, length and ends are enough to see a change
        List<double[]> line = r.polyline();
        sb.append(" polyline=").append(line.size()).append('/').append(r.polylineLength());
        return sb.toString();
    }
}

package net.knightsandkings.knk.core.roads.route;

import net.knightsandkings.knk.core.domain.roads.RoadEdge;

import java.util.Objects;
import java.util.Optional;

/**
 * When no usable route exists, explains why and how far the player can get (DESIGN §6.7 "when no
 * usable route exists", plan 2d): the search is rerun with {@link AccessPolicy#ALL_OPEN}; on that
 * route the first edge the player's real policy blocks is the culprit ("the West Gate is closed",
 * "you may not enter Kardenna Castle"), and the route up to the node before it is the partial
 * route to guide along ("Guiding you to the gate" / "to its edge").
 *
 * <p>Phase 2d decision: the partial route ends at the entry node of the blocked edge (the gate may
 * sit anywhere along that edge; the last reachable <i>node</i> is what the network knows). Oneway
 * rules still apply in the all-open search - a goal unreachable because of them is NO_ROUTE.
 */
public final class BlockedExplainer {

    /**
     * @param verdict      the blocking verdict (reason text + cause)
     * @param blockedEdge  the first blocked edge on the all-open route
     * @param partialRoute the route up to that edge (may be empty: the player's own edge is blocked)
     * @param fullRoute    the all-open route, for "how much is cut off"
     */
    public record Explanation(EdgeVerdict verdict, RoadEdge blockedEdge, Route partialRoute, Route fullRoute) {
        public Explanation {
            Objects.requireNonNull(verdict, "verdict");
            Objects.requireNonNull(blockedEdge, "blockedEdge");
            Objects.requireNonNull(partialRoute, "partialRoute");
            Objects.requireNonNull(fullRoute, "fullRoute");
        }

        /** The blocking element's cause (gate, domain or flag). */
        public EdgeVerdict.Cause cause() {
            return verdict.cause();
        }

        /** "the West Gate is closed" / "you may not enter Kardenna Castle". */
        public String reason() {
            return verdict.message();
        }

        /** Whether the block is a domain the route may not enter (→ "Guiding you to its edge"). */
        public boolean isDomainBlock() {
            return verdict.cause().type() == EdgeVerdict.CauseType.DOMAIN;
        }

        public boolean isGateBlock() {
            return verdict.cause().type() == EdgeVerdict.CauseType.GATE;
        }
    }

    private final AStarRouter router;

    public BlockedExplainer(AStarRouter router) {
        this.router = Objects.requireNonNull(router, "router");
    }

    /** A partial route must end at least this much closer to the goal than the first block's to be preferred. */
    public static final double MIN_CLOSER = 8.0;

    /**
     * Empty when even an all-open search finds nothing (or the all-open route has no blocked edge).
     *
     * <p>Two candidates (live test 2026-10-09, N14): the all-open route up to its first block, and the
     * route the player's real policy allows to the reachable point nearest the goal. The second wins when
     * it ends at least {@link #MIN_CLOSER} blocks closer - a detour round a denied district up to the
     * closed gate beats stopping at the district's edge - and its reason is the first block on the
     * all-open way on from there.
     */
    public Optional<Explanation> explain(RouteRequest request) {
        RouteResult open = router.route(request.withPolicy(AccessPolicy.ALL_OPEN));
        if (!open.isFound()) {
            return Optional.empty();
        }
        Route full = open.route();
        RoadNetworkSnapshot snapshot = router.snapshot();
        AccessPolicy policy = request.accessPolicy();
        Optional<Explanation> first = firstBlock(request, full, full, policy, snapshot, true, null);
        Optional<Route> towards = router.routeTowards(request);
        if (towards.isEmpty()) {
            return first;
        }
        double viaFirst = first.map(e -> distanceToGoals(request, e.partialRoute().end().point()))
            .orElse(distanceToGoals(request, request.start().point()));
        double viaTowards = distanceToGoals(request, towards.get().end().point());
        if (viaTowards > viaFirst - MIN_CLOSER) {
            return first;
        }
        RouteResult onward = router.route(request.withStart(towards.get().end()).withPolicy(AccessPolicy.ALL_OPEN));
        if (!onward.isFound()) {
            return first;
        }
        Optional<Explanation> closer = firstBlock(request.withStart(towards.get().end()), onward.route(), full, policy,
            snapshot, false, towards.get().withVerdicts(snapshot, policy));
        return closer.isPresent() ? closer : first;
    }

    /**
     * The first step of {@code route} the policy blocks: the explanation with the route up to it, or with
     * {@code partial} when given.
     */
    private static Optional<Explanation> firstBlock(RouteRequest request, Route route, Route full, AccessPolicy policy,
                                                    RoadNetworkSnapshot snapshot, boolean truncate, Route partial) {
        for (int i = 0; i < route.steps().size(); i++) {
            Route.Step step = route.steps().get(i);
            EdgeVerdict verdict = policy.check(step.edge());
            if (verdict.isBlocked() && !(i == 0 && request.startStepOpenBySides(step.edge().id(), step.forward()))) {
                Route guide = truncate ? route.truncated(snapshot, i).withVerdicts(snapshot, policy) : partial;
                return Optional.of(new Explanation(verdict, step.edge(), guide, full));
            }
        }
        return Optional.empty();
    }

    private static double distanceToGoals(RouteRequest request, double[] p) {
        double best = Double.POSITIVE_INFINITY;
        for (SnapPoint g : request.goals()) {
            double[] q = g.point();
            double dx = q[0] - p[0], dy = q[1] - p[1], dz = q[2] - p[2];
            best = Math.min(best, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        return best;
    }
}

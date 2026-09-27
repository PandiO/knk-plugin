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

    /** Empty when even an all-open search finds nothing (or the all-open route has no blocked edge). */
    public Optional<Explanation> explain(RouteRequest request) {
        RouteResult open = router.route(request.withPolicy(AccessPolicy.ALL_OPEN));
        if (!open.isFound()) {
            return Optional.empty();
        }
        Route full = open.route();
        RoadNetworkSnapshot snapshot = router.snapshot();
        AccessPolicy policy = request.accessPolicy();
        for (int i = 0; i < full.steps().size(); i++) {
            Route.Step step = full.steps().get(i);
            EdgeVerdict verdict = policy.check(step.edge());
            if (verdict.isBlocked()) {
                Route partial = full.truncated(snapshot, i).withVerdicts(snapshot, policy);
                return Optional.of(new Explanation(verdict, step.edge(), partial, full));
            }
        }
        return Optional.empty();
    }
}

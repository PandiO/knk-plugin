package net.knightsandkings.knk.core.roads.route;

import java.util.Objects;
import java.util.Optional;

/**
 * Outcome of a {@link RouteRequest}.
 *
 * @param status      what happened
 * @param route       the route for {@link Status#FOUND}; the partial route (as far as the player
 *                    can go) for {@link Status#BLOCKED}; {@code null} otherwise
 * @param explanation why the network is blocked, for {@link Status#BLOCKED}; {@code null} otherwise
 */
public record RouteResult(Status status, Route route, BlockedExplainer.Explanation explanation) {

    public enum Status {
        /** A usable route exists. */
        FOUND,
        /**
         * No usable route, but one exists ignoring availability: {@code explanation} names the first
         * blocked element and {@code route} leads to the last reachable point before it.
         */
        BLOCKED,
        /** Start and goals lie in different network components (DESIGN §5.8): refused without searching. */
        DIFFERENT_COMPONENTS,
        /** No route even ignoring availability (or the oneway rules make the goal unreachable). */
        NO_ROUTE
    }

    public RouteResult {
        Objects.requireNonNull(status, "status");
        if (status == Status.FOUND && route == null) {
            throw new IllegalArgumentException("FOUND needs a route");
        }
        if (status == Status.BLOCKED && (route == null || explanation == null)) {
            throw new IllegalArgumentException("BLOCKED needs the partial route and the explanation");
        }
    }

    public static RouteResult found(Route route) {
        return new RouteResult(Status.FOUND, route, null);
    }

    public static RouteResult blocked(BlockedExplainer.Explanation explanation) {
        return new RouteResult(Status.BLOCKED, explanation.partialRoute(), explanation);
    }

    public static RouteResult differentComponents() {
        return new RouteResult(Status.DIFFERENT_COMPONENTS, null, null);
    }

    public static RouteResult noRoute() {
        return new RouteResult(Status.NO_ROUTE, null, null);
    }

    public boolean isFound() {
        return status == Status.FOUND;
    }

    /** The route to guide along: the full one, or the partial one of a BLOCKED result. */
    public Optional<Route> routeToGuide() {
        return Optional.ofNullable(route);
    }

    public Optional<BlockedExplainer.Explanation> explanationOptional() {
        return Optional.ofNullable(explanation);
    }
}

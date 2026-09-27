package net.knightsandkings.knk.core.roads.route;

import net.knightsandkings.knk.core.domain.roads.RoadClass;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One routing question (plan 2d): from a snapped start to any of one or more snapped goals
 * (a destination point, or a region's goal set - DESIGN §6.3), under a player's
 * {@link AccessPolicy}, with the configured class costs.
 *
 * @param start        where the player is on the network
 * @param goals        one or more goal points; the search stops at the first one reached
 * @param accessPolicy the player's availability rules (built per request)
 * @param classCost    routing factor per road class (edges without a profile use 1.0)
 */
public record RouteRequest(SnapPoint start, List<SnapPoint> goals, AccessPolicy accessPolicy,
                           Map<RoadClass, Double> classCost) {

    public RouteRequest {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(accessPolicy, "accessPolicy");
        Objects.requireNonNull(classCost, "classCost");
        goals = List.copyOf(goals);
        if (goals.isEmpty()) {
            throw new IllegalArgumentException("a route request needs at least one goal");
        }
        classCost = Map.copyOf(classCost);
    }

    /** Single goal with the parameters' class costs. */
    public static RouteRequest of(SnapPoint start, SnapPoint goal, AccessPolicy policy, RouterParameters parameters) {
        return new RouteRequest(start, List.of(goal), policy, parameters.classCost());
    }

    /** Several goals with the parameters' class costs. */
    public static RouteRequest of(SnapPoint start, List<SnapPoint> goals, AccessPolicy policy,
                                  RouterParameters parameters) {
        return new RouteRequest(start, goals, policy, parameters.classCost());
    }

    public RouteRequest withPolicy(AccessPolicy policy) {
        return new RouteRequest(start, goals, policy, classCost);
    }

    public RouteRequest withStart(SnapPoint newStart) {
        return new RouteRequest(newStart, goals, accessPolicy, classCost);
    }
}

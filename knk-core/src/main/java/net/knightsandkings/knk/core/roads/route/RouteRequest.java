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
 * <p>Rev. 7 Part A: navigation routes on the routing view, where a gate door or a region border cuts the
 * edge, so a start or a goal on the open side of a block lies on an open edge of its own. The start and goal
 * "sides" that patched whole-edge verdicts (live test 2026-10-08/09, N6, N14) are gone.
 *
 * @param start        where the player is on the network
 * @param goals        one or more goal points; the search stops at the first one reached
 * @param accessPolicy the player's availability rules (built per request)
 * @param classCost    routing factor per road class (edges without a profile use 1.0)
 * @param mode         off-road phase 4 (#707, DESIGN § 5.4): which costs the route is planned with;
 *                     {@link RouteMode#DEFAULT} ({@code preferred}, today's routing) for every caller that names none
 */
public record RouteRequest(SnapPoint start, List<SnapPoint> goals, AccessPolicy accessPolicy,
                           Map<RoadClass, Double> classCost, RouteMode mode) {

    public RouteRequest {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(accessPolicy, "accessPolicy");
        Objects.requireNonNull(classCost, "classCost");
        Objects.requireNonNull(mode, "mode");
        goals = List.copyOf(goals);
        if (goals.isEmpty()) {
            throw new IllegalArgumentException("a route request needs at least one goal");
        }
        classCost = Map.copyOf(classCost);
    }

    /** A request in the default mode ({@code preferred}, today's routing). */
    public RouteRequest(SnapPoint start, List<SnapPoint> goals, AccessPolicy accessPolicy,
                        Map<RoadClass, Double> classCost) {
        this(start, goals, accessPolicy, classCost, RouteMode.DEFAULT);
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
        return new RouteRequest(start, goals, policy, classCost, mode);
    }

    public RouteRequest withStart(SnapPoint newStart) {
        return new RouteRequest(newStart, goals, accessPolicy, classCost, mode);
    }

    /** This request in another {@link RouteMode} (#707). */
    public RouteRequest withMode(RouteMode newMode) {
        return new RouteRequest(start, goals, accessPolicy, classCost, newMode);
    }
}

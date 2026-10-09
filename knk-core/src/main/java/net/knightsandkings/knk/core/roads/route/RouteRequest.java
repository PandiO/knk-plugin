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
 * @param startSides   when the policy blocks the start edge: which of its two parts, from the start
 *                     point to each node, the player may still walk (live test 2026-10-08, N6); null
 *                     = the whole start edge follows the policy
 * @param goalSides    per goal (same order; null entries allowed), when the policy blocks the goal's edge:
 *                     from which node the part up to the goal may be walked (live test 2026-10-09, N14);
 *                     null = goals on blocked edges are unreachable
 */
public record RouteRequest(SnapPoint start, List<SnapPoint> goals, AccessPolicy accessPolicy,
                           Map<RoadClass, Double> classCost, StartSides startSides, List<GoalSides> goalSides) {

    /**
     * The open parts of a blocked start edge: the gate or the domain edge lies on one side of the
     * player, the other side can still be walked to its node.
     *
     * @param towardFrom the part from the start point back to the edge's From node is open
     * @param towardTo   the part from the start point on to the edge's To node is open
     */
    public record StartSides(boolean towardFrom, boolean towardTo) {
        /** Whether the part walked in this direction ({@code forward} = towards To) is open. */
        public boolean open(boolean forward) {
            return forward ? towardTo : towardFrom;
        }
    }

    /**
     * The open approaches to a goal on a blocked edge (the closed gate lies beyond the goal, or the
     * denied region starts past it).
     *
     * @param fromFrom the part from the edge's From node up to the goal is open
     * @param fromTo   the part from the edge's To node back to the goal is open
     */
    public record GoalSides(boolean fromFrom, boolean fromTo) {
        /** Whether the goal may be reached walking in this direction ({@code forward} = from the From node). */
        public boolean open(boolean forward) {
            return forward ? fromFrom : fromTo;
        }
    }

    public RouteRequest(SnapPoint start, List<SnapPoint> goals, AccessPolicy accessPolicy, Map<RoadClass, Double> classCost) {
        this(start, goals, accessPolicy, classCost, null, null);
    }

    public RouteRequest(SnapPoint start, List<SnapPoint> goals, AccessPolicy accessPolicy, Map<RoadClass, Double> classCost,
                        StartSides startSides) {
        this(start, goals, accessPolicy, classCost, startSides, null);
    }

    public RouteRequest {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(accessPolicy, "accessPolicy");
        Objects.requireNonNull(classCost, "classCost");
        goals = List.copyOf(goals);
        if (goals.isEmpty()) {
            throw new IllegalArgumentException("a route request needs at least one goal");
        }
        classCost = Map.copyOf(classCost);
        if (goalSides != null) {
            if (goalSides.size() != goals.size()) {
                throw new IllegalArgumentException("goalSides must have one entry per goal");
            }
            goalSides = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(goalSides));
        }
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
        return new RouteRequest(start, goals, policy, classCost, startSides, goalSides);
    }

    /** A new start drops the start sides (they belong to the old start point). */
    public RouteRequest withStart(SnapPoint newStart) {
        return new RouteRequest(newStart, goals, accessPolicy, classCost, null, goalSides);
    }

    public RouteRequest withStartSides(StartSides sides) {
        return new RouteRequest(start, goals, accessPolicy, classCost, sides, goalSides);
    }

    public RouteRequest withGoalSides(List<GoalSides> sides) {
        return new RouteRequest(start, goals, accessPolicy, classCost, startSides, sides);
    }

    /** Whether goal {@code k} may be reached over its (blocked) edge walking in direction {@code forward}. */
    public boolean goalOpenBySides(int k, boolean forward) {
        GoalSides sides = goalSides == null ? null : goalSides.get(k);
        return sides != null && sides.open(forward);
    }

    /**
     * Whether the route's first step - the start edge walked in direction {@code forward} - is open
     * by the start sides although the policy blocks the edge as a whole.
     */
    public boolean startStepOpenBySides(int edgeId, boolean forward) {
        return startSides != null && edgeId == start.edgeId() && startSides.open(forward);
    }
}

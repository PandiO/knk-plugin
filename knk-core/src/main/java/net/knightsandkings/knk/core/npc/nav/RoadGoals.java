package net.knightsandkings.knk.core.npc.nav;

import java.util.List;
import java.util.Objects;

import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.roads.route.RoadGoalChoice;
import net.knightsandkings.knk.core.roads.route.SnapPoint;

/**
 * The road points an NPC's route to a point destination may end at (link G1, PandiO/knk-workspace#814): today's goal
 * (the end of the road route the router planned) and the off-road chain's candidates ({@link
 * RoadGoalChoice#candidatesAlongRoad}: the nearest point of each road edge within reach of the destination, and the
 * road's nodes onward from today's goal). The NPC service plans a road route and one walk search to each, and the least
 * road plus {@code road-goal-walk-weight} times walk wins ({@link RoadGoalChoice#best(List, double)}), as for players
 * with {@code navigation.road-goal-reachable}.
 * <p>
 * Paper: the road network the router reads, {@code navigation.max-snap-distance} as the reach. Main thread, cheap (no
 * chunks, no search).
 */
public interface RoadGoals {

    /**
     * One road point to try.
     *
     * @param floor the road point as a floor point (the road graph's convention), in the destination's world
     * @param snap  the road point on the network
     */
    record Candidate(NavPoint floor, SnapPoint snap) {
        public Candidate {
            Objects.requireNonNull(floor, "floor");
            Objects.requireNonNull(snap, "snap");
        }
    }

    /**
     * The candidates for a destination off the road, today's goal first, then nearest first; at most {@code max}.
     * Empty when the world has no road network or today's goal is not on it.
     *
     * @param roadEnd   the end of today's road route (a floor point)
     * @param goalFloor the destination's floor point
     */
    List<Candidate> candidates(NavPoint roadEnd, NavPoint goalFloor, int max);
}

package net.knightsandkings.knk.core.production.transport;

import java.time.Instant;
import java.util.List;

/**
 * A measured lane as {@code POST api/TransportLanes/report} takes it. A reachable lane has its {@code roadDistance} in
 * blocks; an unreachable one has none and is never used as a route by the API.
 *
 * @param roadDistance     the walk from the from structure to the road, along the road, and from the road to the to
 *                         structure; null when unreachable
 * @param straightDistance the straight 3D distance between the two positions; null when they are in different worlds
 * @param routeEdgeIds     the road edges walked, in order, each once
 * @param why              why it is unreachable (logged and shown, not sent)
 */
public record LaneMeasurement(int fromStructureId, int toStructureId, boolean reachable, Double roadDistance,
                              Double straightDistance, List<Integer> routeEdgeIds, Instant measuredAt, String why) {

    public LaneMeasurement {
        routeEdgeIds = routeEdgeIds == null ? List.of() : List.copyOf(routeEdgeIds);
        if (reachable && roadDistance == null) {
            throw new IllegalArgumentException("a reachable lane needs a road distance");
        }
        if (!reachable) {
            roadDistance = null;
        }
    }
}

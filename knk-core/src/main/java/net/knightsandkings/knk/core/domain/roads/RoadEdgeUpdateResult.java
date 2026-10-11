package net.knightsandkings.knk.core.domain.roads;

import java.util.List;
import java.util.Objects;

/**
 * What {@code PUT api/road-edges/{id}} answers. Mirrors the web-api's
 * {@code RoadEdgeUpdateResultDto}. Bukkit-free.
 *
 * @param edge           the edge after the update
 * @param changedEdgeIds every edge the update touched: the edge itself plus, with
 *                       {@code propagate}, the edges relabelled along the road
 */
public record RoadEdgeUpdateResult(RoadEdge edge, List<Integer> changedEdgeIds) {
    public RoadEdgeUpdateResult {
        Objects.requireNonNull(edge, "edge");
        changedEdgeIds = List.copyOf(Objects.requireNonNull(changedEdgeIds, "changedEdgeIds"));
    }
}

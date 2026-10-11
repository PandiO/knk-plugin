package net.knightsandkings.knk.core.domain.roads;

import java.util.List;
import java.util.Objects;

/**
 * What {@code POST api/road-edges/prune} answers. Mirrors the web-api's
 * {@code RoadEdgePruneResultDto}. Bukkit-free.
 *
 * @param tombstones     one {@link RoadNodeKind#PRUNED_EDGE} tombstone per pruned edge, in request order
 * @param deletedNodeIds detected junctions and dead ends left without any edge, deleted with them
 */
public record RoadEdgePruneResult(List<RoadNode> tombstones, List<Integer> deletedNodeIds) {
    public RoadEdgePruneResult {
        tombstones = List.copyOf(Objects.requireNonNull(tombstones, "tombstones"));
        deletedNodeIds = List.copyOf(Objects.requireNonNull(deletedNodeIds, "deletedNodeIds"));
    }
}

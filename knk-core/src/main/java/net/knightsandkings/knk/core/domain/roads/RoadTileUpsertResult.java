package net.knightsandkings.knk.core.domain.roads;

import java.util.List;
import java.util.Objects;

/**
 * What {@code PUT api/road-tiles/{world}/{x}/{z}/graph} answers after a build upload (Phase 1
 * status). Mirrors the web-api's {@code RoadTileUpsertResultDto}. Bukkit-free.
 *
 * @param tile            the tile after the upsert (new {@code version})
 * @param nodesCreated    payload nodes that had no match
 * @param nodesUpdated    payload nodes matched to existing nodes
 * @param nodesDeleted    Detected nodes of the tile the payload no longer contains
 * @param edgesCreated    payload edges that had no match
 * @param edgesUpdated    payload edges matched to existing edges (admin fields kept)
 * @param edgesDeleted    Detected edges of the tile the payload no longer contains
 * @param stitchEdges     stitch edges (re)created to neighbour tiles (plan D7)
 * @param labelledEdges   edges that carry a street after labelling (plan D6)
 * @param unlabelledEdges edges without a street
 * @param conflicts       label vote conflicts, as texts (also appended to the tile warnings)
 * @param deletedNodes    the deleted nodes, for the admin's "rebuild removed X" message
 * @param bumpedTileIds   neighbour tiles whose version was bumped because they lost a stitch edge
 */
public record RoadTileUpsertResult(RoadTile tile, int nodesCreated, int nodesUpdated, int nodesDeleted,
                                   int edgesCreated, int edgesUpdated, int edgesDeleted, int stitchEdges,
                                   int labelledEdges, int unlabelledEdges, List<String> conflicts,
                                   List<RoadNode> deletedNodes, List<Integer> bumpedTileIds) {
    public RoadTileUpsertResult {
        Objects.requireNonNull(tile, "tile");
        conflicts = List.copyOf(Objects.requireNonNull(conflicts, "conflicts"));
        deletedNodes = List.copyOf(Objects.requireNonNull(deletedNodes, "deletedNodes"));
        bumpedTileIds = List.copyOf(Objects.requireNonNull(bumpedTileIds, "bumpedTileIds"));
    }
}

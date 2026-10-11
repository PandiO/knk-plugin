package net.knightsandkings.knk.core.domain.roads;

import java.util.List;
import java.util.Objects;

/**
 * One tile's graph download (DESIGN §3.8, plan D3): the tile's own nodes and the edges it owns,
 * including Stitch edges whose other node belongs to a neighbour tile (Phase 1 decision 1) — resolve
 * those ids across downloaded tiles. Edges with {@link RoadEdge#stale()} are still routable.
 * Mirrors the web-api's {@code RoadTileGraphDto}. Bukkit-free.
 */
public record RoadTileGraph(RoadTile tile, List<RoadNode> nodes, List<RoadEdge> edges) {
    public RoadTileGraph {
        Objects.requireNonNull(tile, "tile");
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
        edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
    }

    /** The ETag of this download ({@link RoadTile#etag()}). */
    public String etag() {
        return tile.etag();
    }
}

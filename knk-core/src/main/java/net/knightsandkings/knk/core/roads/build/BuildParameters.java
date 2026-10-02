package net.knightsandkings.knk.core.roads.build;

/**
 * Tunables of one tile build, with the DESIGN §4 {@code navigation.builder} defaults. Phase 3's
 * {@code NavigationConfig} (reuse map R16) fills it from {@code config.yml}; tests use
 * {@link #defaults()} or a smaller {@link #tileSize()}.
 *
 * @param tileSize              side of a tile in blocks ({@code tile-size}, 512 in production; the
 *                              tile at {@code (tileX, tileZ)} covers {@code tileX·size … tileX·size+size-1})
 * @param tileMargin            how far beyond the tile the mask may grow ({@code tile-margin}) so roads
 *                              that leave and re-enter the tile are seen and border crossings are exact
 * @param maxCellsPerTile       cap on mask spans ({@code max-cells-per-tile}); hitting it is a warning
 * @param junctionClusterRadius junction candidates within this many skeleton steps merge into one
 *                              Junction ({@code junction-cluster-radius})
 * @param minSpurLength         spurs shorter than {@code max(minSpurLength, local width)} are pruned
 *                              ({@code min-spur-length})
 * @param ambiguousReach        an ambiguous material only counts within this many spans of an
 *                              unambiguous road span ({@code ambiguous-reach}, DESIGN §5.1)
 * @param rdpEpsilon            Ramer-Douglas-Peucker tolerance for edge geometry (DESIGN §5.6: 0.75)
 * @param seedSnapRadius        a seed is matched to the nearest road span within this many blocks in
 *                              x/z (DESIGN §5.4: "a road span within 8 blocks")
 * @param nodeMatchDistance     new nodes are matched to previous nodes within this 3D distance
 *                              (DESIGN §5.7: 3)
 * @param edgeMatchDistance     an edge keeps its id when its polyline stays within this distance of
 *                              the old one (DESIGN §5.7: 2)
 * @param plazaGrowth           a plaza's footprint reaches this many spans beyond its core's own
 *                              clearance, so the edge band of an irregular plaza (corners, bumps,
 *                              lamp posts) joins the plaza junction ({@code plaza-growth}; smoke test
 *                              fix plan 5.5 item 5)
 * @param lockedNodeReach       a node an admin locked (edited, merged into, recorded through) claims
 *                              the builder's nodes within this distance: the nearest one takes its id
 *                              (beyond {@code nodeMatchDistance}), and an unmatched Junction or
 *                              Endpoint joined to it by a chain this short merges into it, so the
 *                              cleanup survives a rebuild ({@code locked-node-reach}; fix plan 5.5 item 6)
 */
public record BuildParameters(int tileSize, int tileMargin, int maxCellsPerTile, int junctionClusterRadius,
                              int minSpurLength, int ambiguousReach, double rdpEpsilon, int seedSnapRadius,
                              double nodeMatchDistance, double edgeMatchDistance, int plazaGrowth,
                              double lockedNodeReach) {

    public static final int DEFAULT_TILE_SIZE = 512;
    public static final int DEFAULT_TILE_MARGIN = 32;
    public static final int DEFAULT_MAX_CELLS_PER_TILE = 250_000;
    public static final int DEFAULT_JUNCTION_CLUSTER_RADIUS = 3;
    public static final int DEFAULT_MIN_SPUR_LENGTH = 4;
    public static final int DEFAULT_AMBIGUOUS_REACH = 3;
    public static final double DEFAULT_RDP_EPSILON = 0.75;
    public static final int DEFAULT_SEED_SNAP_RADIUS = 8;
    public static final double DEFAULT_NODE_MATCH_DISTANCE = 3.0;
    public static final double DEFAULT_EDGE_MATCH_DISTANCE = 2.0;
    public static final int DEFAULT_PLAZA_GROWTH = 2;
    public static final double DEFAULT_LOCKED_NODE_REACH = 8.0;

    public BuildParameters {
        if (tileSize < 1) throw new IllegalArgumentException("tileSize must be >= 1");
        if (tileMargin < 0) throw new IllegalArgumentException("tileMargin must be >= 0");
        if (maxCellsPerTile < 1) throw new IllegalArgumentException("maxCellsPerTile must be >= 1");
        if (junctionClusterRadius < 0) throw new IllegalArgumentException("junctionClusterRadius must be >= 0");
        if (minSpurLength < 0) throw new IllegalArgumentException("minSpurLength must be >= 0");
        if (ambiguousReach < 0) throw new IllegalArgumentException("ambiguousReach must be >= 0");
        if (!(rdpEpsilon >= 0)) throw new IllegalArgumentException("rdpEpsilon must be >= 0");
        if (seedSnapRadius < 0) throw new IllegalArgumentException("seedSnapRadius must be >= 0");
        if (!(nodeMatchDistance >= 0)) throw new IllegalArgumentException("nodeMatchDistance must be >= 0");
        if (!(edgeMatchDistance >= 0)) throw new IllegalArgumentException("edgeMatchDistance must be >= 0");
        if (plazaGrowth < 0) throw new IllegalArgumentException("plazaGrowth must be >= 0");
        if (!(lockedNodeReach >= 0)) throw new IllegalArgumentException("lockedNodeReach must be >= 0");
    }

    /** The DESIGN §4 defaults. */
    public static BuildParameters defaults() {
        return new BuildParameters(DEFAULT_TILE_SIZE, DEFAULT_TILE_MARGIN, DEFAULT_MAX_CELLS_PER_TILE,
            DEFAULT_JUNCTION_CLUSTER_RADIUS, DEFAULT_MIN_SPUR_LENGTH, DEFAULT_AMBIGUOUS_REACH,
            DEFAULT_RDP_EPSILON, DEFAULT_SEED_SNAP_RADIUS, DEFAULT_NODE_MATCH_DISTANCE,
            DEFAULT_EDGE_MATCH_DISTANCE, DEFAULT_PLAZA_GROWTH, DEFAULT_LOCKED_NODE_REACH);
    }

    /** The defaults with another tile size and margin (tests build small tiles). */
    public BuildParameters withTile(int size, int margin) {
        return new BuildParameters(size, margin, maxCellsPerTile, junctionClusterRadius, minSpurLength,
            ambiguousReach, rdpEpsilon, seedSnapRadius, nodeMatchDistance, edgeMatchDistance, plazaGrowth, lockedNodeReach);
    }

    /** The same parameters with another cell cap. */
    public BuildParameters withMaxCells(int maxCells) {
        return new BuildParameters(tileSize, tileMargin, maxCells, junctionClusterRadius, minSpurLength,
            ambiguousReach, rdpEpsilon, seedSnapRadius, nodeMatchDistance, edgeMatchDistance, plazaGrowth, lockedNodeReach);
    }

    /** The same parameters with another ambiguity reach. */
    public BuildParameters withAmbiguousReach(int reach) {
        return new BuildParameters(tileSize, tileMargin, maxCellsPerTile, junctionClusterRadius, minSpurLength,
            reach, rdpEpsilon, seedSnapRadius, nodeMatchDistance, edgeMatchDistance, plazaGrowth, lockedNodeReach);
    }

    /** The same parameters with other junction/spur rules. */
    public BuildParameters withGraphRules(int clusterRadius, int minSpur) {
        return new BuildParameters(tileSize, tileMargin, maxCellsPerTile, clusterRadius, minSpur,
            ambiguousReach, rdpEpsilon, seedSnapRadius, nodeMatchDistance, edgeMatchDistance, plazaGrowth, lockedNodeReach);
    }

    /** The same parameters with another plaza growth. */
    public BuildParameters withPlazaGrowth(int growth) {
        return new BuildParameters(tileSize, tileMargin, maxCellsPerTile, junctionClusterRadius, minSpurLength,
            ambiguousReach, rdpEpsilon, seedSnapRadius, nodeMatchDistance, edgeMatchDistance, growth, lockedNodeReach);
    }

    /** The same parameters with another locked-node reach. */
    public BuildParameters withLockedNodeReach(double reach) {
        return new BuildParameters(tileSize, tileMargin, maxCellsPerTile, junctionClusterRadius, minSpurLength,
            ambiguousReach, rdpEpsilon, seedSnapRadius, nodeMatchDistance, edgeMatchDistance, plazaGrowth, reach);
    }
}

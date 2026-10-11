package net.knightsandkings.knk.core.domain.roads;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * One 512-block road tile as the API lists it (DESIGN §3.3, plan D4). Mirrors the web-api's
 * {@code RoadTileDto}. Bukkit-free.
 *
 * @param id             the API id
 * @param world          Bukkit world name
 * @param tileX          tile x ({@code floor(blockX / 512)})
 * @param tileZ          tile z
 * @param version        bumped on every build, dirty mark and stitch change; the graph download's
 *                       ETag is this number quoted ({@link #etag()})
 * @param builtAt        when the tile was last built, or {@code null} if never
 * @param builderVersion the plugin builder version that produced the last build
 * @param dirty          a block change was reported since the last build (plan Phase 3 dirty tracker)
 * @param cellCount      road cells the last build found
 * @param nodeCount      nodes owned by the tile
 * @param edgeCount      edges owned by the tile (including its stitch edges)
 * @param levelCount     vertical road levels the last build found (bridges/tunnels)
 * @param warnings       builder warnings of the last build plus the API's label conflicts
 * @param state          {@link RoadTileState#CURATED}: a build of this (built) tile makes a proposal
 *                       instead of replacing the graph (plan §5.7 D1)
 * @param curatedAt      when the tile was first curated, or {@code null}
 */
public record RoadTile(int id, String world, int tileX, int tileZ, int version, OffsetDateTime builtAt,
                       int builderVersion, boolean dirty, int cellCount, int nodeCount, int edgeCount,
                       int levelCount, List<String> warnings, RoadTileState state, OffsetDateTime curatedAt) {
    /** Tile edge length in blocks (DESIGN §3.3). */
    public static final int SIZE = 512;

    public RoadTile {
        Objects.requireNonNull(world, "world");
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
        state = state == null ? RoadTileState.DETECTED : state;
    }

    /** A Detected tile (before rev. 6). */
    public RoadTile(int id, String world, int tileX, int tileZ, int version, OffsetDateTime builtAt,
                    int builderVersion, boolean dirty, int cellCount, int nodeCount, int edgeCount,
                    int levelCount, List<String> warnings) {
        this(id, world, tileX, tileZ, version, builtAt, builderVersion, dirty, cellCount, nodeCount, edgeCount,
            levelCount, warnings, RoadTileState.DETECTED, null);
    }

    /** Whether a build of this tile makes a proposal: it is curated and has been built (plan §5.7 D1). */
    public boolean proposesChanges() {
        return state == RoadTileState.CURATED && isBuilt();
    }

    /** The ETag the API sends for this version: the version number in double quotes ({@code "3"}). */
    public String etag() {
        return "\"" + version + "\"";
    }

    /** Whether the tile has ever been built (a never-built tile is only known through a dirty mark). */
    public boolean isBuilt() {
        return builtAt != null;
    }

    /** Tile coordinate of a block coordinate ({@code floor(block / 512)}). */
    public static int tileCoordinate(int block) {
        return Math.floorDiv(block, SIZE);
    }
}

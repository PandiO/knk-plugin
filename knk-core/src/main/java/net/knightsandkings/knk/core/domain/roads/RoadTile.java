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
 */
public record RoadTile(int id, String world, int tileX, int tileZ, int version, OffsetDateTime builtAt,
                       int builderVersion, boolean dirty, int cellCount, int nodeCount, int edgeCount,
                       int levelCount, List<String> warnings) {
    /** Tile edge length in blocks (DESIGN §3.3). */
    public static final int SIZE = 512;

    public RoadTile {
        Objects.requireNonNull(world, "world");
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
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

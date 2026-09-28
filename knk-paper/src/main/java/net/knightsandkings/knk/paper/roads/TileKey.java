package net.knightsandkings.knk.paper.roads;

import java.util.Objects;

import net.knightsandkings.knk.core.domain.roads.RoadTile;

/**
 * One road tile of one world (road navigation, DESIGN §3.3): {@code tileX}/{@code tileZ} are the block
 * coordinates divided by {@link RoadTile#SIZE} (floor division, so negative coordinates work). Pure,
 * Bukkit-free: the cache, the dirty tracker and the build queue all key on it.
 */
public record TileKey(String world, int tileX, int tileZ) {
    public TileKey {
        Objects.requireNonNull(world, "world");
    }

    /** The tile that contains block {@code (blockX, blockZ)}. */
    public static TileKey of(String world, int blockX, int blockZ) {
        return new TileKey(world, RoadTile.tileCoordinate(blockX), RoadTile.tileCoordinate(blockZ));
    }

    public static TileKey of(RoadTile tile) {
        return new TileKey(tile.world(), tile.tileX(), tile.tileZ());
    }

    public int minX() {
        return tileX * RoadTile.SIZE;
    }

    public int minZ() {
        return tileZ * RoadTile.SIZE;
    }

    /** Inclusive. */
    public int maxX() {
        return minX() + RoadTile.SIZE - 1;
    }

    /** Inclusive. */
    public int maxZ() {
        return minZ() + RoadTile.SIZE - 1;
    }

    public boolean contains(int blockX, int blockZ) {
        return blockX >= minX() && blockX <= maxX() && blockZ >= minZ() && blockZ <= maxZ();
    }

    /** Chebyshev distance in tiles to another tile of the same world; {@code Integer.MAX_VALUE} across worlds. */
    public int distanceTo(TileKey other) {
        if (!world.equals(other.world)) {
            return Integer.MAX_VALUE;
        }
        return Math.max(Math.abs(tileX - other.tileX), Math.abs(tileZ - other.tileZ));
    }

    /** {@code <x>_<z>} - the cache file's base name. */
    public String fileName() {
        return tileX + "_" + tileZ;
    }

    @Override
    public String toString() {
        return world + " " + tileX + "," + tileZ;
    }
}

package net.knightsandkings.knk.core.production.terminal;

import java.util.Objects;

/** A block position by world name, used to look containers up. */
public record BlockKey(String world, int x, int y, int z) {

    public BlockKey {
        Objects.requireNonNull(world, "world");
    }

    /** The chunk this block is in, as {@code chunkX, chunkZ} packed in one long. */
    public long chunkKey() {
        return chunkKey(x >> 4, z >> 4);
    }

    public static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    @Override
    public String toString() {
        return world + " " + x + " " + y + " " + z;
    }
}

package net.knightsandkings.knk.core.production.survey;

/**
 * Port: the blocks a survey reads (production DESIGN § 15 item 2, PandiO/knk-workspace#679). The Paper adapter captures
 * {@code ChunkSnapshot}s on the main thread and hands this view to the survey thread; nothing here touches the live
 * world, so it is safe to read from any thread.
 */
public interface BlockSnapshotSource {

    /** Lowest block y of the world (inclusive). */
    int minY();

    /** Height limit of the world (exclusive, like {@code World.getMaxHeight()}). */
    int maxY();

    /** A captured chunk, or null when it was not captured (unloaded and not loaded for the survey). */
    ChunkBlocks chunk(int chunkX, int chunkZ);

    /** One captured chunk; coordinates are chunk-local x and z (0-15) and world y. */
    interface ChunkBlocks {

        /** The block's material name ({@code OAK_LOG}). */
        String material(int localX, int y, int localZ);

        /** The biome name at the block ({@code RIVER}). */
        String biome(int localX, int y, int localZ);

        /** True for a still water source block (level 0), not flowing water or a waterlogged block. */
        boolean waterSource(int localX, int y, int localZ);

        /** The y of the highest non-air block of the column (for the open-sky share of water). */
        int highestY(int localX, int localZ);
    }

    /** Material at a world position through {@code source}, or null when its chunk was not captured or y is out of range. */
    static String materialAt(BlockSnapshotSource source, int x, int y, int z) {
        if (y < source.minY() || y >= source.maxY()) {
            return null;
        }
        ChunkBlocks chunk = source.chunk(x >> 4, z >> 4);
        return chunk == null ? null : chunk.material(x & 15, y, z & 15);
    }
}

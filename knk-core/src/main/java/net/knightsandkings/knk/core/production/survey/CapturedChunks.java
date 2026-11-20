package net.knightsandkings.knk.core.production.survey;

import java.util.Map;

/** A {@link BlockSnapshotSource} over chunks captured one by one (by {@link #key(int, int)}); missing chunks are null. */
public record CapturedChunks(int minY, int maxY, Map<Long, BlockSnapshotSource.ChunkBlocks> chunks)
        implements BlockSnapshotSource {

    public CapturedChunks {
        chunks = Map.copyOf(chunks);
    }

    public static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    @Override
    public ChunkBlocks chunk(int chunkX, int chunkZ) {
        return chunks.get(key(chunkX, chunkZ));
    }
}

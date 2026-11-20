package net.knightsandkings.knk.paper.production;

import java.util.Locale;

import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;

import net.knightsandkings.knk.core.production.survey.BlockSnapshotSource;

/**
 * One captured chunk for a survey (production DESIGN § 15 item 3.1, PandiO/knk-workspace#679): a {@link ChunkSnapshot}
 * with heightmap and biomes, taken on the main thread. Reading it is thread-safe, so the survey counts on its own
 * thread.
 */
public record ChunkSnapshotBlocks(ChunkSnapshot snapshot) implements BlockSnapshotSource.ChunkBlocks {

    /** Main thread: snapshot of a loaded chunk. */
    public static ChunkSnapshotBlocks capture(Chunk chunk) {
        return new ChunkSnapshotBlocks(chunk.getChunkSnapshot(true, true, false));
    }

    @Override
    public String material(int localX, int y, int localZ) {
        return snapshot.getBlockType(localX, y, localZ).name();
    }

    @Override
    public String biome(int localX, int y, int localZ) {
        var biome = snapshot.getBiome(localX, y, localZ);
        return biome == null ? "UNKNOWN" : biome.getKey().getKey().toUpperCase(Locale.ROOT);
    }

    @Override
    public boolean waterSource(int localX, int y, int localZ) {
        BlockData data = snapshot.getBlockData(localX, y, localZ);
        return data.getMaterial() == Material.WATER && data instanceof Levelled levelled && levelled.getLevel() == 0;
    }

    @Override
    public int highestY(int localX, int localZ) {
        return snapshot.getHighestBlockYAt(localX, localZ);
    }
}

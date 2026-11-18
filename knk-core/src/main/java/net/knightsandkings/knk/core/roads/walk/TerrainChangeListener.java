package net.knightsandkings.knk.core.roads.walk;

/**
 * The block-change hook of off-road navigation (DESIGN § 5.2.3, decision O18): told when blocks changed in a loaded
 * chunk, so what was computed from that terrain (walk links, later the clusters of phase 3) is dropped. The road dirty
 * tracker sees road blocks only (§ 3.5.1), so this is a separate port. The paper side batches block events per chunk
 * and calls it on the main thread; implementations must be cheap and thread-safe.
 */
@FunctionalInterface
public interface TerrainChangeListener {

    /** Blocks changed in chunk ({@code chunkX}, {@code chunkZ}) of {@code world} since the last call for it. */
    void terrainChanged(String world, int chunkX, int chunkZ);
}

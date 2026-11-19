package net.knightsandkings.knk.paper.production;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.util.BoundingBox;

import net.knightsandkings.knk.core.production.survey.AreaShape;
import net.knightsandkings.knk.core.production.survey.BlockSnapshotSource;
import net.knightsandkings.knk.core.production.survey.SurveyCounter;

/**
 * What the survey runner needs from one world (PandiO/knk-workspace#679), main thread only: whether a chunk is loaded,
 * a snapshot of a loaded chunk, loading a chunk without generating it, and the entities in an area. {@link #of(World)}
 * is the Bukkit adapter; tests use a fake.
 */
public interface SurveyWorld {

    String name();

    int minY();

    /** Exclusive. */
    int maxY();

    boolean isLoaded(int chunkX, int chunkZ);

    /** A loaded chunk's snapshot. */
    BlockSnapshotSource.ChunkBlocks capture(int chunkX, int chunkZ);

    /** Loads the chunk (never generates it) and captures it; completes with null when it was never generated. */
    CompletableFuture<BlockSnapshotSource.ChunkBlocks> loadAndCapture(int chunkX, int chunkZ);

    /** The entities in the area's bounding box (loaded chunks only). */
    List<SurveyCounter.EntitySighting> entities(AreaShape shape);

    static SurveyWorld of(World world) {
        return new SurveyWorld() {
            @Override
            public String name() {
                return world.getName();
            }

            @Override
            public int minY() {
                return world.getMinHeight();
            }

            @Override
            public int maxY() {
                return world.getMaxHeight();
            }

            @Override
            public boolean isLoaded(int chunkX, int chunkZ) {
                return world.isChunkLoaded(chunkX, chunkZ);
            }

            @Override
            public BlockSnapshotSource.ChunkBlocks capture(int chunkX, int chunkZ) {
                return ChunkSnapshotBlocks.capture(world.getChunkAt(chunkX, chunkZ));
            }

            @Override
            public CompletableFuture<BlockSnapshotSource.ChunkBlocks> loadAndCapture(int chunkX, int chunkZ) {
                return world.getChunkAtAsync(chunkX, chunkZ, false)
                        .thenApply(chunk -> chunk == null ? null : ChunkSnapshotBlocks.capture(chunk));
            }

            @Override
            public List<SurveyCounter.EntitySighting> entities(AreaShape shape) {
                BoundingBox box = new BoundingBox(shape.minX(), shape.minY(), shape.minZ(), shape.maxX() + 1,
                        shape.maxY() + 1, shape.maxZ() + 1);
                List<SurveyCounter.EntitySighting> seen = new ArrayList<>();
                for (Entity entity : world.getNearbyEntities(box)) {
                    var location = entity.getLocation();
                    seen.add(new SurveyCounter.EntitySighting(entity.getType().name(), location.getBlockX(),
                            location.getBlockY(), location.getBlockZ()));
                }
                return seen;
            }
        };
    }
}

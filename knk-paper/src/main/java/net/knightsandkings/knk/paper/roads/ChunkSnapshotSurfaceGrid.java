package net.knightsandkings.knk.paper.roads;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;

import net.knightsandkings.knk.core.roads.build.GateCells;
import net.knightsandkings.knk.core.roads.build.PassabilityRules;
import net.knightsandkings.knk.core.roads.build.SurfaceGrid;

/**
 * The builder's {@link SurfaceGrid} over a tile's captured chunks (DESIGN §5.4, §9; plan Phase 3):
 * {@link #capture} runs on the main thread with a {@code chunk.getChunkSnapshot(false, false, false)},
 * reduces it at once to the candidate spans ({@link SpanExtractor} → {@link CompactSpans}) and lets the
 * snapshot go; the builder then reads the grid off the main thread.
 *
 * <p>The grid answers only for the cells the extraction describes: a stored span is solid, its two
 * headroom blocks are passable (the extraction required it), the third block above is passable when the
 * span's flag says so, and nothing else is solid or passable. {@code SpanGrid} only ever asks about span
 * candidates, their headroom and the step-up block, so that is enough - the {@link #unknownQueries}
 * counter is there for the test that proves it with a strict fake. {@code floorMaterial} of a non-span
 * throws: the builder must never ask.
 */
public final class ChunkSnapshotSurfaceGrid implements SurfaceGrid {
    private final CompactSpans spans = new CompactSpans();
    private final SpanExtractor extractor;
    private final int minY;
    private final int maxY;
    private final AtomicInteger unknownQueries = new AtomicInteger();

    /**
     * @param rules     passability from config (overlay patterns) with the collidable predicate
     * @param roadFloor whether a material name is a floor material of an enabled profile
     * @param gates     the world's gate-door cells
     * @param minY      {@code World.getMinHeight()}
     * @param maxY      {@code World.getMaxHeight()} (exclusive)
     */
    public ChunkSnapshotSurfaceGrid(PassabilityRules rules, Predicate<String> roadFloor, GateCells gates, int minY, int maxY) {
        this.extractor = new SpanExtractor(rules, roadFloor, gates, minY, maxY);
        this.minY = minY;
        this.maxY = maxY;
    }

    /** The passability rules the extraction uses on the live server: Bukkit's collision flag. */
    public static Predicate<String> bukkitCollidable() {
        return name -> {
            Material material = Material.matchMaterial(name);
            if (material == null) {
                return PassabilityRules.curatedCollidable(name);
            }
            try {
                return material.isCollidable();
            } catch (RuntimeException | LinkageError e) {
                return material.isSolid();
            }
        };
    }

    // ===== capture (main thread) =====

    /** Extracts one chunk snapshot; returns the spans found. The snapshot is not retained. */
    public int capture(ChunkSnapshot snapshot, int chunkX, int chunkZ) {
        Objects.requireNonNull(snapshot, "snapshot");
        SpanExtractor.BlockSource source = (lx, y, lz) -> snapshot.getBlockType(lx, y, lz).name();
        return extractor.extract(source, chunkX, chunkZ, spans);
    }

    /** Records a chunk that has nothing to extract (not generated, or outside the world). */
    public void captureEmpty(int chunkX, int chunkZ) {
        spans.markEmpty(chunkX, chunkZ);
    }

    public boolean hasChunk(int chunkX, int chunkZ) {
        return spans.hasChunk(chunkX, chunkZ);
    }

    public CompactSpans spans() {
        return spans;
    }

    public int unknownQueries() {
        return unknownQueries.get();
    }

    // ===== SurfaceGrid (any thread, after capture) =====

    @Override
    public boolean isPassable(int x, int y, int z) {
        // Headroom of a span: the extraction required blocks +1 and +2 passable; +3 carries a flag.
        if (spans.isSpan(x, y - 1, z) || spans.isSpan(x, y - 2, z)) {
            return true;
        }
        if (spans.hasFlag(x, y - 3, z, CompactSpans.FLAG_THIRD_PASSABLE)) {
            return true;
        }
        if (spans.isSpan(x, y, z)) {
            return false; // a floor is not passable
        }
        if (spans.flags(x, y - 3, z) >= 0) {
            return false; // third block above a span, flagged blocked
        }
        unknownQueries.incrementAndGet();
        return false;
    }

    @Override
    public boolean isSolid(int x, int y, int z) {
        return spans.isSpan(x, y, z);
    }

    @Override
    public boolean isHazard(int x, int y, int z) {
        // The extraction rejects hazardous floors and hazardous headroom, so nothing stored is a hazard.
        if (!spans.isSpan(x, y, z) && !spans.isSpan(x, y - 1, z) && !spans.isSpan(x, y - 2, z)
                && spans.flags(x, y - 3, z) < 0) {
            unknownQueries.incrementAndGet();
        }
        return false;
    }

    @Override
    public int minY() {
        return minY;
    }

    @Override
    public int maxY() {
        return maxY;
    }

    @Override
    public String floorMaterial(int x, int y, int z) {
        String material = spans.material(x, y, z);
        if (material == null) {
            throw new IllegalStateException("floorMaterial asked for a non-span cell " + x + "," + y + "," + z);
        }
        return material;
    }

    @Override
    public boolean isStairOrSlab(int x, int y, int z) {
        return spans.hasFlag(x, y, z, CompactSpans.FLAG_STAIR_OR_SLAB);
    }
}

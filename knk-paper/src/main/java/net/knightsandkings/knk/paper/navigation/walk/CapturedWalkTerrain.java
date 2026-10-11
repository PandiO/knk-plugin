package net.knightsandkings.knk.paper.navigation.walk;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import net.knightsandkings.knk.core.roads.build.GateCells;
import net.knightsandkings.knk.core.roads.build.SurfaceGrid;
import net.knightsandkings.knk.core.roads.walk.WalkCells;
import net.knightsandkings.knk.core.roads.walk.WalkTerrain;

/**
 * The walk search's {@link SurfaceGrid} and {@link WalkCells} over a request's captured
 * {@link WalkChunk}s (KNG-51 {@code LAST_MILE_PATHFINDING.md} §3, §8). Immutable after construction,
 * no Bukkit: safe on the routing thread.
 *
 * <p>Outside the captured chunks and section bands a block is <em>neither passable nor solid</em>
 * (and no hazard, door, climbable or water): it can carry no cell and give no headroom, so the
 * search simply finds nothing there — the box is the search's boundary. Such reads are counted
 * ({@link #outsideQueries}, a diagnostic: a search near the box edge reads the neighbouring columns).
 * {@code floorMaterial} of a block the capture did not record as a possible floor throws, like the
 * builder's {@code CompactSurfaceGrid}: the search only asks it about solid blocks with a walk-passable
 * block above, which the capture always records.
 */
public final class CapturedWalkTerrain implements SurfaceGrid, WalkCells {

    private final Map<Long, WalkChunk> chunks;
    private final GateCells gates;
    private final int minY;
    private final int maxY;
    private final AtomicInteger outsideQueries = new AtomicInteger();

    /**
     * @param chunks the captured chunks of the request (later ones replace earlier ones at the same position)
     * @param gates  the world's gate-door cells (the {@link WalkTerrain}'s third part)
     * @param minY   {@code World.getMinHeight()}
     * @param maxY   {@code World.getMaxHeight()} (exclusive)
     */
    public CapturedWalkTerrain(Collection<WalkChunk> chunks, GateCells gates, int minY, int maxY) {
        Map<Long, WalkChunk> byKey = new HashMap<>();
        for (WalkChunk chunk : Objects.requireNonNull(chunks, "chunks")) {
            byKey.put(WalkChunk.key(chunk.chunkX(), chunk.chunkZ()), chunk);
        }
        this.chunks = Map.copyOf(byKey);
        this.gates = gates == null ? GateCells.NONE : gates;
        this.minY = minY;
        this.maxY = maxY;
    }

    /** The core's view: this grid, the gate cells, and this as the {@link WalkCells}. */
    public WalkTerrain terrain() {
        return new WalkTerrain(this, gates, this);
    }

    public int chunkCount() {
        return chunks.size();
    }

    /** Reads of blocks outside the captured chunks/bands since construction. */
    public int outsideQueries() {
        return outsideQueries.get();
    }

    private int flags(int x, int y, int z) {
        WalkChunk chunk = chunks.get(WalkChunk.key(x >> 4, z >> 4));
        if (chunk == null || !chunk.contains(x, y, z)) {
            outsideQueries.incrementAndGet();
            return 0;
        }
        return chunk.flags(x, y, z);
    }

    // ===== SurfaceGrid =====

    @Override
    public boolean isPassable(int x, int y, int z) {
        return (flags(x, y, z) & WalkChunk.PASSABLE) != 0;
    }

    @Override
    public boolean isSolid(int x, int y, int z) {
        return (flags(x, y, z) & WalkChunk.SOLID) != 0;
    }

    @Override
    public boolean isHazard(int x, int y, int z) {
        return (flags(x, y, z) & WalkChunk.HAZARD) != 0;
    }

    @Override
    public boolean isStairOrSlab(int x, int y, int z) {
        return (flags(x, y, z) & WalkChunk.STAIR_OR_SLAB) != 0;
    }

    @Override
    public String floorMaterial(int x, int y, int z) {
        WalkChunk chunk = chunks.get(WalkChunk.key(x >> 4, z >> 4));
        String material = chunk == null ? null : chunk.floorMaterial(x, y, z);
        if (material == null) {
            throw new IllegalStateException("floorMaterial asked for a block the capture did not record as a floor "
                + x + "," + y + "," + z);
        }
        return material;
    }

    @Override
    public int minY() {
        return minY;
    }

    @Override
    public int maxY() {
        return maxY;
    }

    // ===== WalkCells =====

    @Override
    public boolean isDoor(int x, int y, int z) {
        return (flags(x, y, z) & WalkChunk.DOOR) != 0;
    }

    @Override
    public boolean isClimbable(int x, int y, int z) {
        return (flags(x, y, z) & WalkChunk.CLIMBABLE) != 0;
    }

    @Override
    public boolean isWater(int x, int y, int z) {
        return (flags(x, y, z) & WalkChunk.WATER) != 0;
    }
}

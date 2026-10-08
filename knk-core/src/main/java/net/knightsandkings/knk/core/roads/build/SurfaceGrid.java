package net.knightsandkings.knk.core.roads.build;

import net.knightsandkings.knk.core.util.BlockProbe;

/**
 * Read-only view of a world's blocks for the road builder (DESIGN §5.2, §5.4). knk-paper implements
 * it over captured {@code ChunkSnapshot}s ({@code roads/ChunkSnapshotSurfaceGrid}, Phase 3) so that
 * everything in this package runs off the main thread and stays Bukkit-free and unit-testable
 * (the test fixture builds one from ASCII layers).
 *
 * <p>{@code isPassable}, {@code isSolid}, {@code isHazard}, {@code minY()} and {@code maxY()}
 * (exclusive) are {@link BlockProbe}'s - teleport's probe, shared since Phase 4 (reuse map R22);
 * this interface only adds the material and stair/slab questions the builder needs.
 *
 * <p>All coordinates are block coordinates. Materials are plain names ({@code Material.name()} on
 * the paper side), so the rules over them live in {@link PassabilityRules}.
 */
public interface SurfaceGrid extends BlockProbe {

    /**
     * Material name of the floor at this block, "looking through" overlays: the block at {@code y}
     * itself, or the block at {@code y - 1} when the block at {@code y} is an overlay (a carpet on
     * stone bricks reports {@code STONE_BRICKS}). Never null ({@code AIR} for air).
     */
    String floorMaterial(int x, int y, int z);

    /** The block is a stair or a slab (a step onto it needs no jump, DESIGN §5.2). */
    boolean isStairOrSlab(int x, int y, int z);
}

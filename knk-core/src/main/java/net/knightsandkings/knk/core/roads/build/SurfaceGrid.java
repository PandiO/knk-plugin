package net.knightsandkings.knk.core.roads.build;

/**
 * Read-only view of a world's blocks for the road builder (DESIGN §5.2, §5.4). knk-paper implements
 * it over captured {@code ChunkSnapshot}s ({@code roads/ChunkSnapshotSurfaceGrid}, Phase 3) so that
 * everything in this package runs off the main thread and stays Bukkit-free and unit-testable
 * (the test fixture builds one from ASCII layers).
 *
 * <p>The first five methods are exactly teleport's {@code BlockProbe} (reuse map R22: same names,
 * signatures and semantics, {@link #maxY()} exclusive), so Phase 4 can make this interface
 * {@code extends BlockProbe} without changing an implementation.
 *
 * <p>All coordinates are block coordinates. Materials are plain names ({@code Material.name()} on
 * the paper side), so the rules over them live in {@link PassabilityRules}.
 */
public interface SurfaceGrid {

    /**
     * A player can stand in this block: it has no collision box ({@code Material#isCollidable()} is
     * false) or it is a thin overlay (carpet, snow layer, pressure plate, rail …). Gate-door blocks
     * are <em>not</em> required to be passable here — {@link SpanGrid} folds the {@link GateCells}
     * port in itself.
     */
    boolean isPassable(int x, int y, int z);

    /** A player can stand on top of this block (collidable and not an overlay). */
    boolean isSolid(int x, int y, int z);

    /** Standing in or on this block hurts (teleport's hazard set, {@link PassabilityRules#HAZARD_MATERIALS}). */
    boolean isHazard(int x, int y, int z);

    /** Lowest buildable Y (inclusive). */
    int minY();

    /** Highest buildable Y (exclusive), like Bukkit's {@code World.getMaxHeight()}. */
    int maxY();

    /**
     * Material name of the floor at this block, "looking through" overlays: the block at {@code y}
     * itself, or the block at {@code y - 1} when the block at {@code y} is an overlay (a carpet on
     * stone bricks reports {@code STONE_BRICKS}). Never null ({@code AIR} for air).
     */
    String floorMaterial(int x, int y, int z);

    /** The block is a stair or a slab (a step onto it needs no jump, DESIGN §5.2). */
    boolean isStairOrSlab(int x, int y, int z);
}

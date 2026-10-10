package net.knightsandkings.knk.core.util;

/**
 * Read-only view of a world's blocks, so the rules over them stay Bukkit-free and unit-testable:
 * teleport's {@code core/teleport/SafeLocationFinder} (knk-paper implements it over a Bukkit
 * {@code World}, {@code teleport/BukkitBlockProbe}) and the road builder's {@code SurfaceGrid},
 * which extends it (road navigation plan §2 R22 - moved here from {@code core/teleport} in Phase 4,
 * unchanged). All coordinates are block coordinates.
 */
public interface BlockProbe {

    /** A player can stand in this block (air, grass, open door, water...). */
    boolean isPassable(int x, int y, int z);

    /** A player can stand on top of this block. */
    boolean isSolid(int x, int y, int z);

    /** Standing in or on this block hurts (see {@code SafeLocationFinder#HAZARD_MATERIALS}). */
    boolean isHazard(int x, int y, int z);

    /** Lowest buildable Y (inclusive). */
    int minY();

    /** Highest buildable Y (exclusive), like Bukkit's {@code World.getMaxHeight()}. */
    int maxY();
}

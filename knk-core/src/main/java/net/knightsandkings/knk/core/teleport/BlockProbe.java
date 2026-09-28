package net.knightsandkings.knk.core.teleport;

/**
 * Read-only view of a world's blocks for {@link SafeLocationFinder}, so the safety rules stay
 * Bukkit-free and unit-testable. knk-paper implements it over a Bukkit {@code World}
 * ({@code teleport/BukkitBlockProbe}). All coordinates are block coordinates.
 */
public interface BlockProbe {

    /** A player can stand in this block (air, grass, open door, water...). */
    boolean isPassable(int x, int y, int z);

    /** A player can stand on top of this block. */
    boolean isSolid(int x, int y, int z);

    /** Standing in or on this block hurts (see {@link SafeLocationFinder#HAZARD_MATERIALS}). */
    boolean isHazard(int x, int y, int z);

    /** Lowest buildable Y (inclusive). */
    int minY();

    /** Highest buildable Y (exclusive), like Bukkit's {@code World.getMaxHeight()}. */
    int maxY();
}

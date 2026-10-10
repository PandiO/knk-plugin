package net.knightsandkings.knk.paper.utils;

import java.util.Optional;
import java.util.OptionalDouble;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.siege.SiegeFloor;

/**
 * Generic conversions between knk {@link KnkLocation}s and Bukkit locations (road navigation plan §2
 * R10: extracted from {@code SiegeBukkit}, which delegates here unchanged).
 */
public final class KnkLocations {
    private KnkLocations() {
    }

    /** A runtime-config location in a loaded world, or empty (missing coordinates or world not loaded). */
    public static Optional<Location> toLocation(KnkLocation location) {
        if (location == null || location.x() == null || location.y() == null || location.z() == null) {
            return Optional.empty();
        }
        World world = location.world() != null ? Bukkit.getWorld(location.world()) : null;
        if (world == null) return Optional.empty();
        return Optional.of(new Location(world, location.x(), location.y(), location.z(),
                location.yaw() != null ? location.yaw() : 0f,
                location.pitch() != null ? location.pitch() : 0f));
    }

    /**
     * The point snapped to the floor under it ({@link SiegeFloor}): a point placed in the air comes down
     * to the ground. Unchanged when the chunk isn't loaded or no floor is within reach.
     */
    public static Location floorOf(Location point) {
        World world = point.getWorld();
        if (world == null) return point;
        int x = point.getBlockX();
        int z = point.getBlockZ();
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return point;
        OptionalDouble floor = SiegeFloor.floorY(point.getY(),
                by -> by >= world.getMinHeight() && by < world.getMaxHeight() && !world.getBlockAt(x, by, z).isPassable(),
                by -> world.getBlockAt(x, by, z).getBoundingBox().getMaxY());
        if (floor.isEmpty()) return point;
        Location snapped = point.clone();
        snapped.setY(floor.getAsDouble());
        return snapped;
    }
}

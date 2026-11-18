package net.knightsandkings.knk.core.navigation.model;

import java.util.Objects;
import java.util.Optional;

import net.knightsandkings.knk.core.domain.location.KnkLocation;

/**
 * A point in the navigation model (destination engine DESIGN § 7.2): a {@code Location} target or a domain's spawn
 * Location, always with coordinates and a world (a Location without them is no place to navigate to).
 *
 * @param id    the Location's id; null when unknown
 * @param name  the Location's name; null when it has none
 * @param world the world name, never blank
 */
public record ModelPoint(Integer id, String name, double x, double y, double z, float yaw, float pitch, String world) {

    public ModelPoint {
        Objects.requireNonNull(world, "world");
        if (world.isBlank()) {
            throw new IllegalArgumentException("world is blank");
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("coordinates must be finite");
        }
    }

    /** The API's Location as a point; empty when it lacks a world or a coordinate (or is null). */
    public static Optional<ModelPoint> of(KnkLocation location) {
        if (location == null || location.world() == null || location.world().isBlank()
            || location.x() == null || location.y() == null || location.z() == null
            || !Double.isFinite(location.x()) || !Double.isFinite(location.y()) || !Double.isFinite(location.z())) {
            return Optional.empty();
        }
        return Optional.of(new ModelPoint(location.id(), location.name(), location.x(), location.y(), location.z(),
            location.yaw() == null ? 0f : location.yaw(), location.pitch() == null ? 0f : location.pitch(),
            location.world()));
    }

    /** Back to the shape the rest of the plugin uses ({@code DomainLocationResolver}, {@code NavTarget}). */
    public KnkLocation toKnkLocation() {
        return new KnkLocation(id, name, x, y, z, yaw, pitch, world);
    }
}

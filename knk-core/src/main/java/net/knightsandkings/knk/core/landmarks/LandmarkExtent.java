package net.knightsandkings.knk.core.landmarks;

import java.util.Objects;

import net.knightsandkings.knk.core.navigation.model.Landmark;

/**
 * Where a landmark counts as reached (off-road DESIGN § 6.2.1, § 6.3; PandiO/knk-workspace#708), Bukkit-free: its
 * pin only ({@link Point}: within {@code arrive-distance}), a WorldGuard region ({@link Region}: the region test is the
 * game server's), or a cylinder around the pin ({@link Cylinder}). Phase 5.2 weights walk cells inside an extent.
 */
public sealed interface LandmarkExtent permits LandmarkExtent.Point, LandmarkExtent.Region, LandmarkExtent.Cylinder {

    /** The extent of {@code landmark}; a Region without a region id or a Radius without a usable radius is a {@link Point}. */
    static LandmarkExtent of(Landmark landmark) {
        Objects.requireNonNull(landmark, "landmark");
        return switch (landmark.extent()) {
            case REGION -> landmark.regionId() == null || landmark.regionId().isBlank()
                ? new Point() : new Region(landmark.regionId());
            case RADIUS -> landmark.radius() == null || !(landmark.radius() > 0) || !Double.isFinite(landmark.radius())
                ? new Point() : new Cylinder(landmark.x(), landmark.y(), landmark.z(), landmark.radius());
            case NONE -> new Point();
        };
    }

    /** No extent: the pin, arrived at within {@code arrive-distance}. */
    record Point() implements LandmarkExtent {
    }

    /** A WorldGuard region of the landmark's world. */
    record Region(String regionId) implements LandmarkExtent {
        public Region {
            Objects.requireNonNull(regionId, "regionId");
        }
    }

    /**
     * A vertical cylinder around the pin (the sub-region spec's cylinder shape): {@code radius} blocks across, and as
     * many blocks above and below the pin's feet, so a landmark on one floor of a keep does not count the floors far
     * above or below it.
     */
    record Cylinder(double x, double y, double z, double radius) implements LandmarkExtent {
        public Cylinder {
            if (!(radius > 0) || !Double.isFinite(radius)) {
                throw new IllegalArgumentException("radius must be positive and finite: " + radius);
            }
        }

        /** Whether the feet position is inside. */
        public boolean contains(double px, double py, double pz) {
            return contains(x, y, z, radius, px, py, pz);
        }

        /** The same test for a centre and radius given apart (the navigation session's destination). */
        public static boolean contains(double cx, double cy, double cz, double radius, double px, double py, double pz) {
            double dx = px - cx;
            double dz = pz - cz;
            return dx * dx + dz * dz <= radius * radius && Math.abs(py - cy) <= radius;
        }
    }
}

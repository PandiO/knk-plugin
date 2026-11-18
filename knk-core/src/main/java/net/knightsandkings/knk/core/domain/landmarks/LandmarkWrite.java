package net.knightsandkings.knk.core.domain.landmarks;

import java.util.Objects;

import net.knightsandkings.knk.core.navigation.model.Landmark;

/**
 * The body of {@code POST api/landmarks} and {@code PUT api/landmarks/{id}} (PandiO/knk-workspace#708). The pin is
 * a {@link Pin}: without {@code locationId} the API creates a Location, with one it moves that Location. On a
 * {@code PUT} the API keeps a stored value for each null field except {@code domainId}, which it takes as sent (null
 * unlinks), so an update always carries the domain it should keep ({@link #from}).
 *
 * @param name              required
 * @param pin               the pin to create or move; null keeps the stored one (update only)
 * @param domainId          the domain link (null: none)
 * @param extent            null keeps the stored extent (update only); NONE clears it
 * @param regionId          for a Region extent
 * @param radius            for a Radius extent
 * @param navigationEnabled null keeps the stored value
 */
public record LandmarkWrite(String name, Pin pin, Integer domainId, Landmark.Extent extent, String regionId, Double radius,
                            Boolean navigationEnabled) {

    public LandmarkWrite {
        Objects.requireNonNull(name, "name");
    }

    /**
     * A Location to create ({@code locationId} null) or move (its id).
     *
     * @param locationId the Location to move; null creates one
     */
    public record Pin(Integer locationId, String world, double x, double y, double z, float yaw, float pitch) {
        public Pin {
            Objects.requireNonNull(world, "world");
        }
    }

    /** An update of {@code landmark} that keeps everything not given: its name, its domain, its extent. */
    public static LandmarkWrite from(KnkLandmark landmark) {
        return new LandmarkWrite(landmark.name(), null, landmark.domainId(), null, null, null, null);
    }

    public LandmarkWrite withPin(Pin newPin) {
        return new LandmarkWrite(name, newPin, domainId, extent, regionId, radius, navigationEnabled);
    }

    public LandmarkWrite withDomain(Integer newDomainId) {
        return new LandmarkWrite(name, pin, newDomainId, extent, regionId, radius, navigationEnabled);
    }

    public LandmarkWrite withExtent(Landmark.Extent newExtent, String newRegionId, Double newRadius) {
        return new LandmarkWrite(name, pin, domainId, newExtent, newRegionId, newRadius, navigationEnabled);
    }
}

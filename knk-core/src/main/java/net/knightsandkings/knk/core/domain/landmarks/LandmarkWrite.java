package net.knightsandkings.knk.core.domain.landmarks;

import java.util.Objects;

/**
 * The body of {@code POST api/landmarks} and {@code PUT api/landmarks/{id}} (since PandiO/knk-workspace#985 with the
 * landmark-domain field names: {@code parentDomainId}, {@code areaKind}, {@code wgRegionId}). The pin is a {@link Pin}:
 * without {@code locationId} the API creates a Location, with one it moves that Location. On a {@code PUT} the API
 * keeps a stored value for each null field except {@code parentDomainId}, which it takes as sent (null unlinks), so an
 * update always carries the parent it should keep ({@link #from}). There is no radius area any more (LD08).
 *
 * @param name              required
 * @param pin               the pin to create or move; null keeps the stored one (update only)
 * @param parentDomainId    the parent (linked) domain (null: none)
 * @param areaKind          null keeps the stored area (update only); NONE clears it
 * @param wgRegionId        the own region for {@code OWN_REGION}
 * @param navigationEnabled null keeps the stored value
 */
public record LandmarkWrite(String name, Pin pin, Integer parentDomainId, LandmarkAreaKind areaKind, String wgRegionId,
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

    /** An update of {@code landmark} that keeps everything not given: its name, its parent, its area. */
    public static LandmarkWrite from(KnkLandmark landmark) {
        return new LandmarkWrite(landmark.name(), null, landmark.parentDomainId(), null, null, null);
    }

    public LandmarkWrite withPin(Pin newPin) {
        return new LandmarkWrite(name, newPin, parentDomainId, areaKind, wgRegionId, navigationEnabled);
    }

    public LandmarkWrite withParent(Integer newParentDomainId) {
        return new LandmarkWrite(name, pin, newParentDomainId, areaKind, wgRegionId, navigationEnabled);
    }

    public LandmarkWrite withArea(LandmarkAreaKind newAreaKind, String newRegionId) {
        return new LandmarkWrite(name, pin, parentDomainId, newAreaKind, newRegionId, navigationEnabled);
    }
}

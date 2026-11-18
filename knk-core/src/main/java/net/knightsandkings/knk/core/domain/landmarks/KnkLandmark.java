package net.knightsandkings.knk.core.domain.landmarks;

import java.util.Objects;

import net.knightsandkings.knk.core.navigation.model.Landmark;

/**
 * A landmark as the web API holds it (off-road navigation phase 5.1, PandiO/knk-workspace#708, DESIGN § 6.3;
 * {@code GET api/landmarks}): a named place made by staff, pinned to a Location, optionally linked to a domain and
 * optionally with an extent. What {@code /knk landmark} reads and writes; the navigation model keeps the smaller
 * {@link Landmark} (no Location id; the preference since phase 5.2), see {@link #toModel()}.
 *
 * @param id                the landmark's id (its destination key is {@code landmark:<id>})
 * @param name              unique per world
 * @param locationId        the pin's Location id; null when the API sent none
 * @param world             the pin's world
 * @param x                 the pin (feet)
 * @param yaw               the pin's facing
 * @param domainId          the linked domain; null when none
 * @param domainName        its name; null when none
 * @param domainType        its type ({@code Town}, {@code Structure}, ...); null when none
 * @param extent            None, Region or Radius
 * @param regionId          the WorldGuard region for a Region extent, else null
 * @param radius            blocks for a Radius extent, else null
 * @param preference        the walk-cost multiplier inside the extent (0.25 to 4.0; in the model since phase 5.2)
 * @param navigationEnabled false: not offered as a destination
 */
public record KnkLandmark(int id, String name, Integer locationId, String world, double x, double y, double z,
                          float yaw, float pitch, Integer domainId, String domainName, String domainType,
                          Landmark.Extent extent, String regionId, Double radius, double preference,
                          boolean navigationEnabled) {

    public KnkLandmark {
        Objects.requireNonNull(name, "name");
        extent = extent == null ? Landmark.Extent.NONE : extent;
    }

    /** The navigation model's form (destination engine § 3.2, E16); null without a world (it can't be routed to). */
    public Landmark toModel() {
        if (world == null || world.isBlank()) {
            return null;
        }
        return new Landmark(id, name, world, x, y, z, extent, regionId, radius, domainId, domainName, navigationEnabled,
            preference);
    }
}

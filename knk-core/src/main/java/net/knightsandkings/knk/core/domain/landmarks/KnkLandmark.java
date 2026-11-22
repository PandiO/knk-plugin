package net.knightsandkings.knk.core.domain.landmarks;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.knightsandkings.knk.core.navigation.model.Landmark;

/**
 * A landmark as the web API holds it ({@code GET api/landmarks}): since PandiO/knk-workspace#985 (landmark-domain
 * DESIGN § 6, § 7) a {@code Domain} of type {@code Landmark}, pinned to its own Location, with an optional parent (the
 * linked domain, LD09) and an area ({@link LandmarkAreaKind}). What {@code /knk landmark} reads and writes; the
 * navigation model keeps the smaller {@link Landmark}, see {@link #toModel()}.
 *
 * @param id                     the landmark's domain id (its destination key is {@code landmark:<id>})
 * @param name                   unique per world among landmarks
 * @param locationId             the pin's Location id; null when the API sent none
 * @param world                  the pin's world
 * @param x                      the pin (feet)
 * @param yaw                    the pin's facing
 * @param parentDomainId         the parent (linked) domain; null for a top-level landmark
 * @param parentDomainName       its name; null when none
 * @param parentDomainType       its type ({@code Town}, {@code Structure}, {@code Landmark}, ...); null when none
 * @param parentRegionId         the parent's main region ({@code parentDomain.wgRegionId}); null when none
 * @param areaKind               None, OwnRegion or LinkedDomain
 * @param wgRegionId             the landmark's own main region (OwnRegion only, else null)
 * @param subRegionIds           its own sub-regions (OwnRegion only), in order
 * @param showEnterLeaveMessages LD15: its enter and leave messages are on (default off)
 * @param preference             the walk-cost multiplier inside the area (0.25 to 4.0)
 * @param navigationEnabled      false: not offered as a destination
 */
public record KnkLandmark(int id, String name, Integer locationId, String world, double x, double y, double z,
                          float yaw, float pitch, Integer parentDomainId, String parentDomainName,
                          String parentDomainType, String parentRegionId, LandmarkAreaKind areaKind, String wgRegionId,
                          List<String> subRegionIds, boolean showEnterLeaveMessages, double preference,
                          boolean navigationEnabled) {

    public KnkLandmark {
        Objects.requireNonNull(name, "name");
        areaKind = areaKind == null ? LandmarkAreaKind.NONE : areaKind;
        wgRegionId = blankToNull(wgRegionId);
        parentRegionId = blankToNull(parentRegionId);
        subRegionIds = clean(subRegionIds);
    }

    /**
     * The regions of the landmark's area (LD22), main region first: its own main region and sub-regions with
     * {@code OwnRegion}, the parent's main region with {@code LinkedDomain} (the parent's sub-regions are found on the
     * game server through their {@code knk-domain-id} flag), none for a point. Empty when the region is missing.
     */
    public List<String> areaRegionIds() {
        return switch (areaKind) {
            case OWN_REGION -> {
                if (wgRegionId == null) {
                    yield List.of();
                }
                List<String> ids = new ArrayList<>();
                ids.add(wgRegionId);
                ids.addAll(subRegionIds);
                yield Landmark.cleanRegionIds(ids);
            }
            case LINKED_DOMAIN -> parentRegionId == null ? List.of() : List.of(parentRegionId);
            case NONE -> List.of();
        };
    }

    /** The navigation model's form (destination engine § 3.2, E16); null without a world (it can't be routed to). */
    public Landmark toModel() {
        if (world == null || world.isBlank()) {
            return null;
        }
        return new Landmark(id, name, world, x, y, z, areaRegionIds(), parentDomainId, parentDomainName, navigationEnabled,
            preference);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static List<String> clean(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String id : ids) {
            if (id != null && !id.isBlank()) {
                out.add(id.trim());
            }
        }
        return List.copyOf(out);
    }
}

package net.knightsandkings.knk.core.navigation.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A landmark in the navigation model (destination engine DESIGN § 3.2 and § 7.2, E16; off-road DESIGN § 6; since
 * PandiO/knk-workspace#985 the landmark-domain model, DESIGN § 7.1 item 4, LD08, LD22). Filled from
 * {@code api/landmarks} with {@code navigation.landmarks} on.
 *
 * @param id                the landmark's id (its candidate key is {@code landmark:<id>}, stable across renames)
 * @param name              its name (unique per world)
 * @param world             the world of its point
 * @param x                 its point (the Location it is pinned to)
 * @param regionIds         the regions of its area, main region first (LD22): its own main region and sub-regions,
 *                          or the parent's main region for a {@code LinkedDomain} landmark; empty for a point, which
 *                          is arrived at within {@code arrive-distance}. The game server adds the other regions of
 *                          the same domain (its {@code knk-domain-id} flag) when it resolves the area.
 * @param domainId          the parent (linked) domain; null when none
 * @param domainName        that domain's name (the candidate's {@code in <domain>} detail); null when none
 * @param navigationEnabled false: the landmark exists but is not offered as a destination
 * @param preference        the walk-cost multiplier inside the area (off-road phase 5.2, PandiO/knk-workspace#709,
 *                          DESIGN § 5.4.3; the API allows 0.25 to 4.0); 1.0 (no weight) when missing, not finite or
 *                          not above 0
 */
public record Landmark(int id, String name, String world, double x, double y, double z, List<String> regionIds,
                       Integer domainId, String domainName, boolean navigationEnabled, double preference) {

    /** No weight: a {@code preference} of 1.0. */
    public static final double NO_PREFERENCE = 1.0;

    public Landmark {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(world, "world");
        regionIds = cleanRegionIds(regionIds);
        preference = preference(preference);
    }

    /** A landmark without a preference weight (1.0). */
    public Landmark(int id, String name, String world, double x, double y, double z, List<String> regionIds,
                    Integer domainId, String domainName, boolean navigationEnabled) {
        this(id, name, world, x, y, z, regionIds, domainId, domainName, navigationEnabled, NO_PREFERENCE);
    }

    /** A stored or sent preference as the model holds it: missing, not finite or not above 0 is {@link #NO_PREFERENCE}. */
    public static double preference(Double value) {
        return value == null || !Double.isFinite(value) || !(value > 0) ? NO_PREFERENCE : value;
    }

    /** Region ids trimmed, blanks dropped, duplicates (ignoring case) dropped, order kept. */
    public static List<String> cleanRegionIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                continue;
            }
            String trimmed = id.trim();
            String key = trimmed.toLowerCase(Locale.ROOT);
            if (!seen.contains(key)) {
                seen.add(key);
                out.add(trimmed);
            }
        }
        return List.copyOf(out);
    }

    /** Whether the landmark has an area of regions (else it is a point). */
    public boolean hasArea() {
        return !regionIds.isEmpty();
    }

    /** The area's main region (own, or the parent's); null for a point. */
    public String mainRegionId() {
        return regionIds.isEmpty() ? null : regionIds.get(0);
    }

    /** The same landmark with the area's regions {@code ids} (the game server's resolved union). */
    public Landmark withRegionIds(List<String> ids) {
        return new Landmark(id, name, world, x, y, z, ids, domainId, domainName, navigationEnabled, preference);
    }

    /** Whether this landmark weights walk cells: an area and a preference other than 1.0. */
    public boolean weighted() {
        return hasArea() && preference != NO_PREFERENCE;
    }
}

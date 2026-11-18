package net.knightsandkings.knk.core.navigation.model;

import java.util.Locale;
import java.util.Objects;

/**
 * A landmark in the navigation model (destination engine DESIGN § 3.2 and § 7.2, E16; off-road DESIGN § 6.3, O27).
 * Snapshot schema 1 reserves the {@code landmarks} section; it stays empty until off-road phase 5.1
 * (PandiO/knk-workspace#708) fills it from {@code api/landmarks}, so that link adds a provider, not a schema.
 *
 * @param id                the landmark's id (its candidate key is {@code landmark:<id>}, stable across renames)
 * @param name              its name (unique per world)
 * @param world             the world of its point
 * @param x                 its point (the Location it is pinned to)
 * @param extent            how arrival is measured: {@code NONE} (within {@code arrive-distance} of the point),
 *                          {@code RADIUS} (a cylinder of {@code radius}) or {@code REGION} (inside {@code regionId})
 * @param regionId          the WorldGuard region when {@code extent} is {@code REGION}; null otherwise
 * @param radius            blocks when {@code extent} is {@code RADIUS}; null otherwise
 * @param domainId          the domain it is linked to; null when none
 * @param domainName        that domain's name (the candidate's {@code in <domain>} detail); null when none
 * @param navigationEnabled false: the landmark exists but is not offered as a destination
 * @param preference        the walk-cost multiplier inside the extent (off-road phase 5.2, PandiO/knk-workspace#709,
 *                          DESIGN § 5.4.3; the API allows 0.25 to 4.0); 1.0 (no weight) when missing, not finite or
 *                          not above 0
 */
public record Landmark(int id, String name, String world, double x, double y, double z, Extent extent, String regionId,
                       Double radius, Integer domainId, String domainName, boolean navigationEnabled, double preference) {

    /** No weight: a {@code preference} of 1.0. */
    public static final double NO_PREFERENCE = 1.0;

    /** Off-road DESIGN § 6.3 {@code ExtentKind}. */
    public enum Extent {
        NONE,
        REGION,
        RADIUS;

        /** Any case ({@code "Radius"} from the API); null and unknown words are {@code NONE}. */
        public static Extent parse(String value) {
            if (value == null) {
                return NONE;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return NONE;
            }
        }
    }

    public Landmark {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(world, "world");
        extent = extent == null ? Extent.NONE : extent;
        preference = preference(preference);
    }

    /** A landmark without a preference weight (1.0), as before phase 5.2. */
    public Landmark(int id, String name, String world, double x, double y, double z, Extent extent, String regionId,
                    Double radius, Integer domainId, String domainName, boolean navigationEnabled) {
        this(id, name, world, x, y, z, extent, regionId, radius, domainId, domainName, navigationEnabled, NO_PREFERENCE);
    }

    /** A stored or sent preference as the model holds it: missing, not finite or not above 0 is {@link #NO_PREFERENCE}. */
    public static double preference(Double value) {
        return value == null || !Double.isFinite(value) || !(value > 0) ? NO_PREFERENCE : value;
    }

    /** Whether this landmark weights walk cells: an extent (region or radius) and a preference other than 1.0. */
    public boolean weighted() {
        return extent != Extent.NONE && preference != NO_PREFERENCE;
    }
}

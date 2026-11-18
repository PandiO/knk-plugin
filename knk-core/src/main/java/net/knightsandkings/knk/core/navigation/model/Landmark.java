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
 */
public record Landmark(int id, String name, String world, double x, double y, double z, Extent extent, String regionId,
                       Double radius, Integer domainId, String domainName, boolean navigationEnabled) {

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
    }
}

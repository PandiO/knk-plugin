package net.knightsandkings.knk.core.regions.managed;

import java.util.Locale;
import java.util.Objects;

/**
 * A region Knights and Kings owns, as the v3 data (or config) describes it.
 *
 * @param regionId       the WorldGuard region id
 * @param kind           its category
 * @param parentRegionId the WorldGuard id of the region that should be its parent, or null for none
 * @param source         where the spec came from, for logs ({@code Town#5}, {@code config extra-regions})
 */
public record ManagedRegionSpec(String regionId, ManagedRegionKind kind, String parentRegionId, String source) {

    public ManagedRegionSpec {
        Objects.requireNonNull(regionId, "regionId");
        Objects.requireNonNull(kind, "kind");
        source = source != null ? source : "";
    }

    /** WorldGuard region ids are case-insensitive; every comparison here goes through this key. */
    public static String key(String regionId) {
        return regionId == null ? null : regionId.trim().toLowerCase(Locale.ROOT);
    }

    public String key() {
        return key(regionId);
    }
}

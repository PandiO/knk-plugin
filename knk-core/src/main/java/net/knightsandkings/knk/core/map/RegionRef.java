package net.knightsandkings.knk.core.map;

import java.util.Locale;
import java.util.Objects;

/**
 * A region by id and world. {@code worldName} is null when the caller does not know the world (a domain from before
 * KNG-111); the region is then looked up in the loaded worlds in turn.
 */
public record RegionRef(String worldName, String regionId) {

    public RegionRef {
        Objects.requireNonNull(regionId, "regionId");
        regionId = regionId.trim();
        worldName = worldName == null || worldName.isBlank() ? null : worldName.trim();
    }

    /** Region ids are case-insensitive (WorldGuard); world names are compared ignoring case as WorldGuard's managers do. */
    public String key() {
        return (worldName == null ? "" : worldName.toLowerCase(Locale.ROOT)) + "\u0000" + regionId.toLowerCase(Locale.ROOT);
    }
}

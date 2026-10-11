package net.knightsandkings.knk.core.regions.managed;

import java.util.Locale;
import java.util.Objects;

/**
 * A region Knights and Kings owns, as the v3 data (or config) describes it.
 *
 * @param regionId       the WorldGuard region id
 * @param kind           its category
 * @param parentRegionId the WorldGuard id of the region that should be its parent, or null for none (always in the
 *                       same world: WorldGuard parents never cross worlds)
 * @param source         where the spec came from, for logs ({@code Town#5}, {@code config extra-regions})
 * @param worldName      KNG-112: the world the region is in (its domain's world); null for a domain that has no world
 *                       yet, whose region is then looked up in the loaded worlds in turn, as before
 */
public record ManagedRegionSpec(String regionId, ManagedRegionKind kind, String parentRegionId, String source,
                                String worldName) {

    public ManagedRegionSpec {
        Objects.requireNonNull(regionId, "regionId");
        Objects.requireNonNull(kind, "kind");
        source = source != null ? source : "";
        worldName = worldName == null || worldName.isBlank() ? null : worldName.trim();
    }

    /** A spec without a world (config extra-regions, domains from before KNG-111). */
    public ManagedRegionSpec(String regionId, ManagedRegionKind kind, String parentRegionId, String source) {
        this(regionId, kind, parentRegionId, source, null);
    }

    /** WorldGuard region ids are case-insensitive; every comparison here goes through this key. */
    public static String key(String regionId) {
        return regionId == null ? null : regionId.trim().toLowerCase(Locale.ROOT);
    }

    public String key() {
        return key(regionId);
    }

    /**
     * KNG-112: a region's identity - its id in its world. A region id is unique per world only, so the hub's
     * {@code town_1} and the gameplay world's {@code town_1} are two regions. A null world is its own scope.
     */
    public static String scopedKey(String worldName, String regionId) {
        String id = key(regionId);
        if (id == null) {
            return null;
        }
        String world = worldName == null || worldName.isBlank() ? "" : worldName.trim().toLowerCase(Locale.ROOT);
        return world + "\u0000" + id;
    }

    public String scopedKey() {
        return scopedKey(worldName, regionId);
    }

    /** The region for logs and reports: its id, with the world when it has one ({@code town_1 (hub)}). */
    public String label() {
        return worldName == null ? regionId : regionId + " (" + worldName + ")";
    }
}

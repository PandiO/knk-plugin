package net.knightsandkings.knk.core.discovery;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

import net.knightsandkings.knk.core.domain.discovery.DiscoverySource;

/**
 * A WorldGuard region a player was seen in that may be an undiscovered domain, waiting to be
 * sent to the API (or spooled because the API was unreachable).
 *
 * <p>Region ids are unique per world only (KNG-111/KNG-112), so a candidate is identified by
 * {@code (world, regionId)} (KNG-118 R14). The world is null only for entries spooled before that
 * (spool files without a world); those are still sent, world-blind, as before.
 *
 * @param world        the Bukkit world the region is in; null when unknown (an old spool entry)
 * @param regionId     the raw WorldGuard region id
 * @param source       how the player got there
 * @param discoveredAt when the player was seen there (decides full effects vs. a summary on replay)
 */
public record PendingDiscovery(String world, String regionId, DiscoverySource source, Instant discoveredAt) {
    public PendingDiscovery {
        Objects.requireNonNull(regionId, "regionId");
        world = world == null || world.isBlank() ? null : world.trim();
        source = source == null ? DiscoverySource.REGION_ENTER : source;
        discoveredAt = discoveredAt == null ? Instant.EPOCH : discoveredAt;
    }

    /** A candidate whose world isn't known (a spool entry written before KNG-118). */
    public PendingDiscovery(String regionId, DiscoverySource source, Instant discoveredAt) {
        this(null, regionId, source, discoveredAt);
    }

    /** The comparison key: {@link #key(String, String)} of this world and region. */
    public String key() {
        return key(world, regionId);
    }

    /**
     * The comparison key of a region in a world: {@code "<world>:<regionId>"}, both lower-cased
     * (WorldGuard ids and the API's lookups are case-insensitive) and an empty world part when the
     * world is unknown. WorldGuard region ids can't contain {@code ':'}, so keys don't collide.
     */
    public static String key(String world, String regionId) {
        String region = regionId == null ? "" : regionId.trim().toLowerCase(Locale.ROOT);
        if (region.isEmpty()) {
            return "";
        }
        String worldPart = world == null ? "" : world.trim().toLowerCase(Locale.ROOT);
        return worldPart + ":" + region;
    }
}

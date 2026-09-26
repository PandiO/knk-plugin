package net.knightsandkings.knk.core.discovery;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

import net.knightsandkings.knk.core.domain.discovery.DiscoverySource;

/**
 * A WorldGuard region a player was seen in that may be an undiscovered domain, waiting to be
 * sent to the API (or spooled because the API was unreachable).
 *
 * @param regionId     the raw WorldGuard region id
 * @param source       how the player got there
 * @param discoveredAt when the player was seen there (decides full effects vs. a summary on replay)
 */
public record PendingDiscovery(String regionId, DiscoverySource source, Instant discoveredAt) {
    public PendingDiscovery {
        Objects.requireNonNull(regionId, "regionId");
        source = source == null ? DiscoverySource.REGION_ENTER : source;
        discoveredAt = discoveredAt == null ? Instant.EPOCH : discoveredAt;
    }

    /** The comparison key: WorldGuard ids and the API's lookup are both case-insensitive. */
    public String key() {
        return key(regionId);
    }

    public static String key(String regionId) {
        return regionId == null ? "" : regionId.trim().toLowerCase(Locale.ROOT);
    }
}

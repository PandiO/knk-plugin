package net.knightsandkings.knk.core.domain.discovery;

/**
 * A domain the player has discovered, with its WorldGuard region id (null if it has none) and the
 * world that region is in (KNG-118; null when the domain has no world yet, or the API predates it).
 */
public record KnownDiscovery(int domainId, String wgRegionId, String worldName) {
    public KnownDiscovery(int domainId, String wgRegionId) {
        this(domainId, wgRegionId, null);
    }
}

package net.knightsandkings.knk.core.domain.discovery;

/** A domain the player has discovered, with its WorldGuard region id (null if it has none). */
public record KnownDiscovery(int domainId, String wgRegionId) {}

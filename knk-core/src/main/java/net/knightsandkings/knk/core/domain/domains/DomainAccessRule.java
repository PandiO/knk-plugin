package net.knightsandkings.knk.core.domain.domains;

/**
 * One domain's AllowEntry/AllowExit by WorldGuard region ({@code GET /api/Domains/access-rules},
 * KNG-56). The game server writes these onto the regions as flags WorldGuard saves.
 */
public record DomainAccessRule(
    int id,
    String name,
    String wgRegionId,
    boolean allowEntry,
    boolean allowExit,
    String domainType
) {}

package net.knightsandkings.knk.core.domain.domains;

/**
 * Lightweight id/name/subtype view of a Domain, for id-based fetch and name-search browsing
 * (e.g. picking an ItemBlueprint's Origin) - a different responsibility from
 * DomainRegionSummary/DomainsDataAccess, which is keyed by WorldGuard region id.
 */
public record KnkDomainSummary(
        Integer id,
        String name,
        String domainType
) {}

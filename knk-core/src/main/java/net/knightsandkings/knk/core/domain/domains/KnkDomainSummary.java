package net.knightsandkings.knk.core.domain.domains;

/**
 * Lightweight id/name/subtype view of a Domain, for id-based fetch and name-search browsing
 * (e.g. picking an ItemBlueprint's Origin) - a different responsibility from
 * DomainRegionSummary/DomainsDataAccess, which is keyed by WorldGuard region id.
 *
 * @param navigationDefault where {@code /navigate <domain>} leads without {@code spawn}/{@code region}:
 *                          "Spawn" or "Region" (KNG-73); null when the API did not say (getById)
 */
public record KnkDomainSummary(
        Integer id,
        String name,
        String domainType,
        String navigationDefault
) {
    public KnkDomainSummary(Integer id, String name, String domainType) {
        this(id, name, domainType, null);
    }
}

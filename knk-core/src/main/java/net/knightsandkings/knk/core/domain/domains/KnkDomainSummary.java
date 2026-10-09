package net.knightsandkings.knk.core.domain.domains;

/**
 * Lightweight id/name/subtype view of a Domain, for id-based fetch and name-search browsing
 * (e.g. picking an ItemBlueprint's Origin) - a different responsibility from
 * DomainRegionSummary/DomainsDataAccess, which is keyed by WorldGuard region id.
 *
 * @param navigationDefault where {@code /navigate <domain>} leads without {@code spawn}/{@code region}:
 *                          "Spawn" or "Region" (KNG-73); null when the API did not say (getById)
 * @param roadAccess        whether the domain's entry/exit rule keeps routes off its roads: "Applies" or
 *                          "Ignored" (rev. 7 Part C, KNG-92); null when the API did not say
 */
public record KnkDomainSummary(
        Integer id,
        String name,
        String domainType,
        String navigationDefault,
        String roadAccess
) {
    public KnkDomainSummary(Integer id, String name, String domainType) {
        this(id, name, domainType, null, null);
    }

    public KnkDomainSummary(Integer id, String name, String domainType, String navigationDefault) {
        this(id, name, domainType, navigationDefault, null);
    }

    /** Only an explicit "Ignored" lifts the rule off the roads; unknown keeps it. */
    public boolean roadAccessIgnored() {
        return "Ignored".equalsIgnoreCase(roadAccess);
    }
}

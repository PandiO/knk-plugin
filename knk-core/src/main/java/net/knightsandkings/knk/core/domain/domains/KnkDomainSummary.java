package net.knightsandkings.knk.core.domain.domains;

import net.knightsandkings.knk.core.domain.location.KnkLocation;

/**
 * Lightweight id/name/subtype view of a Domain, for id-based fetch and name-search browsing
 * (e.g. picking an ItemBlueprint's Origin) - a different responsibility from
 * DomainRegionSummary/DomainsDataAccess, which is keyed by WorldGuard region id.
 *
 * @param navigationDefault where {@code /navigate <domain>} leads without {@code spawn}/{@code region}:
 *                          "Spawn" or "Region" (KNG-73); null when the API did not say (getById)
 * @param roadAccess        whether the domain's entry/exit rule keeps routes off its roads: "Applies" or
 *                          "Ignored" (rev. 7 Part C, KNG-92); null when the API did not say
 * @param wgRegionId        the domain's WorldGuard region (search only); null when the API did not say
 * @param worldName         the domain's world (KNG-111, search only); null when the API did not say
 * @param location          the domain's own Location (search only, destination engine E12); null when it has none or
 *                          the API did not say - {@code locationSent} tells which
 * @param locationSent      whether the API's answer carried the {@code location} field at all (an API from before
 *                          PandiO/knk-workspace#103 does not): false means "unknown", not "no Location"
 */
public record KnkDomainSummary(
        Integer id,
        String name,
        String domainType,
        String navigationDefault,
        String roadAccess,
        String wgRegionId,
        String worldName,
        KnkLocation location,
        boolean locationSent
) {
    public KnkDomainSummary(Integer id, String name, String domainType, String navigationDefault, String roadAccess,
                            String wgRegionId) {
        this(id, name, domainType, navigationDefault, roadAccess, wgRegionId, null, null, false);
    }

    public KnkDomainSummary(Integer id, String name, String domainType) {
        this(id, name, domainType, null, null, null);
    }

    public KnkDomainSummary(Integer id, String name, String domainType, String navigationDefault) {
        this(id, name, domainType, navigationDefault, null, null);
    }

    public KnkDomainSummary(Integer id, String name, String domainType, String navigationDefault, String roadAccess) {
        this(id, name, domainType, navigationDefault, roadAccess, null);
    }

    /** Only an explicit "Ignored" lifts the rule off the roads; unknown keeps it. */
    public boolean roadAccessIgnored() {
        return "Ignored".equalsIgnoreCase(roadAccess);
    }
}

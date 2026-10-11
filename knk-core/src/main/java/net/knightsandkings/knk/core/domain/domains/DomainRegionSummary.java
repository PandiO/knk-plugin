package net.knightsandkings.knk.core.domain.domains;

import java.util.Collection;

/**
 * @param worldName KNG-112: the world the domain and its region are in; null for a domain the API has no world for yet.
 */
public record DomainRegionSummary (
    Integer id,
    String name,
    String description,
    String wgRegionId,
    Boolean allowEntry,
    Boolean allowExit,
    String domainType,
    Collection<DomainRegionSummary> parentDomainDecisions,
    String worldName
) {
    /** A summary without a world (as the API sent before KNG-111). */
    public DomainRegionSummary(Integer id, String name, String description, String wgRegionId, Boolean allowEntry,
                               Boolean allowExit, String domainType, Collection<DomainRegionSummary> parentDomainDecisions) {
        this(id, name, description, wgRegionId, allowEntry, allowExit, domainType, parentDomainDecisions, null);
    }
}

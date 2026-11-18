package net.knightsandkings.knk.core.domain.domains;

import java.util.Collection;
import java.util.List;

/**
 * @param worldName KNG-112: the world the domain and its region are in; null for a domain the API has no world for yet.
 * @param subRegionIds KNG-256: the domain's other regions (sub-regions, KNG-254), which resolve to the same domain;
 *     never null
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
    String worldName,
    Collection<String> subRegionIds
) {
    public DomainRegionSummary {
        subRegionIds = subRegionIds == null ? List.of()
            : subRegionIds.stream().filter(region -> region != null && !region.isBlank()).toList();
    }

    /** A summary without sub-regions (as the API sent before KNG-254). */
    public DomainRegionSummary(Integer id, String name, String description, String wgRegionId, Boolean allowEntry,
                               Boolean allowExit, String domainType, Collection<DomainRegionSummary> parentDomainDecisions,
                               String worldName) {
        this(id, name, description, wgRegionId, allowEntry, allowExit, domainType, parentDomainDecisions, worldName, List.of());
    }

    /** A summary without a world (as the API sent before KNG-111). */
    public DomainRegionSummary(Integer id, String name, String description, String wgRegionId, Boolean allowEntry,
                               Boolean allowExit, String domainType, Collection<DomainRegionSummary> parentDomainDecisions) {
        this(id, name, description, wgRegionId, allowEntry, allowExit, domainType, parentDomainDecisions, null);
    }
}

package net.knightsandkings.knk.core.domain.domains;

import java.util.Set;

/**
 * Asks {@code POST /api/Domains/search-region-decisions} for the domains of some WorldGuard regions.
 *
 * @param worldName KNG-112: the world the regions are in; only that world's domains match (and domains the API has no
 *     world for yet). Null asks world-blind.
 */
public record DomainRegionQuery(
    Set<String> wgRegionIds,
    Boolean topDownHierarchy,
    String worldName
) {
    /** A world-blind query. */
    public DomainRegionQuery(Set<String> wgRegionIds, Boolean topDownHierarchy) {
        this(wgRegionIds, topDownHierarchy, null);
    }
}

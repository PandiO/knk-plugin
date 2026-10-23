package net.knightsandkings.knk.core.domain.domains;

import java.util.List;

/**
 * One domain's AllowEntry/AllowExit by WorldGuard region ({@code GET /api/Domains/access-rules},
 * KNG-56). The game server writes these onto the regions as flags WorldGuard saves.
 *
 * @param worldName KNG-112: the world of the domain's region; the flags go on that world's region only. Null for a
 *     domain the API has no world for yet: then every loaded world's region with this id gets them, as before.
 * @param subRegionIds KNG-255: the domain's sub-regions (in its world): the same domain, so they carry the same rule
 *     and mirror the main region {@code wgRegionId}. Never null.
 */
public record DomainAccessRule(
    int id,
    String name,
    String wgRegionId,
    boolean allowEntry,
    boolean allowExit,
    String domainType,
    String worldName,
    List<String> subRegionIds
) {
    public DomainAccessRule {
        subRegionIds = subRegionIds == null ? List.of()
            : subRegionIds.stream().filter(region -> region != null && !region.isBlank()).map(String::trim).toList();
    }

    /** A rule without sub-regions (as the API sent before KNG-254). */
    public DomainAccessRule(int id, String name, String wgRegionId, boolean allowEntry, boolean allowExit, String domainType,
                            String worldName) {
        this(id, name, wgRegionId, allowEntry, allowExit, domainType, worldName, List.of());
    }

    /** A rule without a world (as the API sent before KNG-111). */
    public DomainAccessRule(int id, String name, String wgRegionId, boolean allowEntry, boolean allowExit, String domainType) {
        this(id, name, wgRegionId, allowEntry, allowExit, domainType, null);
    }
}

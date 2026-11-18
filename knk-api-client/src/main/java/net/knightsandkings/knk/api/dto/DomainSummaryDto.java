package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Minimal id/name/subtype view of a Domain returned by POST api/Domains/search
 * (DomainListDto.domainType server-side) and, for getById, GET api/Domains/{id} - which returns the
 * raw Domain entity and therefore has no domainType, so that field is null in that path.
 * {@code navigationDefault} is where {@code /navigate <domain>} leads without {@code spawn}/{@code region}:
 * "Spawn" or "Region", the domain's override else its type's default (KNG-73); search only, null otherwise.
 * {@code roadAccess} is "Applies" or "Ignored": whether the domain's entry/exit rule keeps the road router
 * off its roads (rev. 7 Part C, KNG-92); search only, null otherwise. {@code wgRegionId} is the domain's
 * WorldGuard region (search only): the routing view leaves the regions of "Ignored" domains uncut.
 * {@code worldName} is the domain's world (KNG-111). {@code location} is the domain's own Location (destination
 * engine E12, PandiO/knk-workspace#103), kept as the raw JSON node so the mapper can tell an API that sent
 * {@code "location": null} (the domain has none) from an older API without the key (Java null).
 */
public record DomainSummaryDto(
        @JsonProperty("id") Integer id,
        @JsonProperty("name") String name,
        @JsonProperty("domainType") String domainType,
        @JsonProperty("navigationDefault") String navigationDefault,
        @JsonProperty("roadAccess") String roadAccess,
        @JsonProperty("wgRegionId") String wgRegionId,
        @JsonProperty("worldName") String worldName,
        @JsonProperty("location") JsonNode location
) {
    public DomainSummaryDto(Integer id, String name, String domainType, String navigationDefault, String roadAccess,
                            String wgRegionId) {
        this(id, name, domainType, navigationDefault, roadAccess, wgRegionId, null, null);
    }
}

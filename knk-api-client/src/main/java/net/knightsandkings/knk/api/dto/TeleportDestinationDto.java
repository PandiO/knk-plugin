package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One warp destination for one player - knk-web-api {@code TeleportDestinationDto}
 * ({@code GET api/teleport-destinations?userId=}, docs/specs/teleport/DESIGN.md §3.7.3).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TeleportDestinationDto(
    @JsonProperty("domainId") Integer domainId,
    @JsonProperty("name") String name,
    @JsonProperty("domainType") String domainType,
    @JsonProperty("location") Location location,
    @JsonProperty("priceGems") Integer priceGems,
    @JsonProperty("minTitleName") String minTitleName,
    @JsonProperty("minPremiumTierName") String minPremiumTierName,
    @JsonProperty("requiresDiscovery") Boolean requiresDiscovery,
    @JsonProperty("available") Boolean available,
    @JsonProperty("requirementsMet") Boolean requirementsMet,
    @JsonProperty("canAfford") Boolean canAfford,
    @JsonProperty("lockCode") String lockCode,
    @JsonProperty("lockReason") String lockReason
) {
    /** knk-web-api {@code TeleportLocationDto}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Location(
        @JsonProperty("world") String world,
        @JsonProperty("x") Double x,
        @JsonProperty("y") Double y,
        @JsonProperty("z") Double z,
        @JsonProperty("yaw") Float yaw,
        @JsonProperty("pitch") Float pitch
    ) {}
}

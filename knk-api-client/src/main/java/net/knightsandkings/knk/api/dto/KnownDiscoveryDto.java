package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Maps to knk-web-api's KnownDiscoveryDto. */
public record KnownDiscoveryDto(
    @JsonProperty("domainId") int domainId,
    @JsonProperty("wgRegionId") String wgRegionId
) {}

package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Maps to knk-web-api's KnownDiscoveryDto. {@code worldName} is null from an API before KNG-118. */
public record KnownDiscoveryDto(
    @JsonProperty("domainId") int domainId,
    @JsonProperty("wgRegionId") String wgRegionId,
    @JsonProperty("worldName") String worldName
) {}

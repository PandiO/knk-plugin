package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Maps to knk-web-api's DiscoveryTypeCountDto; {@code enabled} is null from an API that predates it. */
public record DiscoveryTypeCountDto(
    @JsonProperty("domainType") String domainType,
    @JsonProperty("discovered") int discovered,
    @JsonProperty("total") int total,
    @JsonProperty("enabled") Boolean enabled
) {}

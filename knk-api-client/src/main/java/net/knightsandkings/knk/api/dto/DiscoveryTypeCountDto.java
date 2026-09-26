package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Maps to knk-web-api's DiscoveryTypeCountDto. */
public record DiscoveryTypeCountDto(
    @JsonProperty("domainType") String domainType,
    @JsonProperty("discovered") int discovered,
    @JsonProperty("total") int total
) {}

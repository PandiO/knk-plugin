package net.knightsandkings.knk.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Maps to knk-web-api's DiscoverySummaryDto. */
public record DiscoverySummaryDto(
    @JsonProperty("byType") List<DiscoveryTypeCountDto> byType,
    @JsonProperty("latest") DiscoveryProgressRowDto latest,
    @JsonProperty("totalDiscovered") int totalDiscovered,
    @JsonProperty("totalCoins") int totalCoins,
    @JsonProperty("totalGems") int totalGems,
    @JsonProperty("totalExp") int totalExp
) {}

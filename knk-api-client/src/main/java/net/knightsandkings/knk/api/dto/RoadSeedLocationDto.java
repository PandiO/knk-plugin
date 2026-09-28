package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Maps to knk-web-api's RoadSeedLocationDto ({@code GET api/road-network/seed-locations}, plan D12). */
public record RoadSeedLocationDto(
    @JsonProperty("domainId") int domainId,
    @JsonProperty("domainType") String domainType,
    @JsonProperty("name") String name,
    @JsonProperty("x") int x,
    @JsonProperty("y") int y,
    @JsonProperty("z") int z
) {}

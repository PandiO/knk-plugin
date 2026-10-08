package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Maps to knk-web-api's RoadComponentDto (a connected component and its node count). */
public record RoadComponentDto(
    @JsonProperty("id") int id,
    @JsonProperty("nodeCount") int nodeCount
) {}

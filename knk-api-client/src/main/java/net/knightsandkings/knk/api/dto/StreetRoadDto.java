package net.knightsandkings.knk.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Maps to knk-web-api's StreetRoadDto ({@code GET api/Streets/{id}/road}): a street's labelled edges and nodes. */
public record StreetRoadDto(
    @JsonProperty("streetId") int streetId,
    @JsonProperty("name") String name,
    @JsonProperty("edgeCount") int edgeCount,
    @JsonProperty("totalLength") double totalLength,
    @JsonProperty("edges") List<RoadEdgeDto> edges,
    @JsonProperty("nodes") List<RoadNodeDto> nodes
) {}

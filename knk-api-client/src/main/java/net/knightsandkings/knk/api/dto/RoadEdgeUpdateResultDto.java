package net.knightsandkings.knk.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Maps to knk-web-api's RoadEdgeUpdateResultDto ({@code PUT api/road-edges/{id}} answer). */
public record RoadEdgeUpdateResultDto(
    @JsonProperty("edge") RoadEdgeDto edge,
    @JsonProperty("changedEdgeIds") List<Integer> changedEdgeIds
) {}

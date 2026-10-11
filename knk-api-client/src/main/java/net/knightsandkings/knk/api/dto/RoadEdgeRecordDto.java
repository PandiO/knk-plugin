package net.knightsandkings.knk.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Body of {@code POST api/road-edges} (knk-web-api's RoadEdgeRecordDto): an admin-walked Recorded
 * edge. A {@code null} {@code length} is left out and the API uses the polyline length.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RoadEdgeRecordDto(
    @JsonProperty("world") String world,
    @JsonProperty("geometry") int[][] geometry,
    @JsonProperty("length") Double length,
    @JsonProperty("avgWidth") double avgWidth,
    @JsonProperty("profileId") Integer profileId,
    @JsonProperty("streetId") Integer streetId,
    @JsonProperty("gateDoorIds") List<Integer> gateDoorIds,
    @JsonProperty("domainIds") List<Integer> domainIds,
    @JsonProperty("regionIds") List<String> regionIds
) {}

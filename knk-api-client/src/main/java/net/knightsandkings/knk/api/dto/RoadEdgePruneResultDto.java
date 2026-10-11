package net.knightsandkings.knk.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Maps to knk-web-api's RoadEdgePruneResultDto ({@code POST api/road-edges/prune} answer). */
public record RoadEdgePruneResultDto(
    @JsonProperty("tombstones") List<RoadNodeDto> tombstones,
    @JsonProperty("deletedNodeIds") List<Integer> deletedNodeIds
) {}

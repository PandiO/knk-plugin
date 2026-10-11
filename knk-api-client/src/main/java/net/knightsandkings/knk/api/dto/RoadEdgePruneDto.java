package net.knightsandkings.knk.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Maps to knk-web-api's RoadEdgePruneDto ({@code POST api/road-edges/prune} body). */
public record RoadEdgePruneDto(
    @JsonProperty("edgeIds") List<Integer> edgeIds
) {}

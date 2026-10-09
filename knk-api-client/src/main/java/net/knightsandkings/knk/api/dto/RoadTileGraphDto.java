package net.knightsandkings.knk.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Maps to knk-web-api's RoadTileGraphDto: the per-tile download (plan D3, ETag/304). */
public record RoadTileGraphDto(
    @JsonProperty("tile") RoadTileDto tile,
    @JsonProperty("nodes") List<RoadNodeDto> nodes,
    @JsonProperty("edges") List<RoadEdgeDto> edges
) {}

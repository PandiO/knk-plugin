package net.knightsandkings.knk.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Maps to knk-web-api's RoadTileUpsertResultDto: what the build upload answers. */
public record RoadTileUpsertResultDto(
    @JsonProperty("tile") RoadTileDto tile,
    @JsonProperty("nodesCreated") int nodesCreated,
    @JsonProperty("nodesUpdated") int nodesUpdated,
    @JsonProperty("nodesDeleted") int nodesDeleted,
    @JsonProperty("edgesCreated") int edgesCreated,
    @JsonProperty("edgesUpdated") int edgesUpdated,
    @JsonProperty("edgesDeleted") int edgesDeleted,
    @JsonProperty("stitchEdges") int stitchEdges,
    @JsonProperty("labelledEdges") int labelledEdges,
    @JsonProperty("unlabelledEdges") int unlabelledEdges,
    @JsonProperty("conflicts") List<String> conflicts,
    @JsonProperty("deletedNodes") List<RoadNodeDto> deletedNodes,
    @JsonProperty("bumpedTileIds") List<Integer> bumpedTileIds
) {}

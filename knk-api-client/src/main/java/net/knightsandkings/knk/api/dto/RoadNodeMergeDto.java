package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Body of {@code POST api/road-nodes/merge} (knk-web-api's RoadNodeMergeDto). */
public record RoadNodeMergeDto(
    @JsonProperty("keepNodeId") int keepNodeId,
    @JsonProperty("mergeNodeId") int mergeNodeId
) {}

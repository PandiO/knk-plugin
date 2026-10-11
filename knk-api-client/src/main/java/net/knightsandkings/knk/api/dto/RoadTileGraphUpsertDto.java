package net.knightsandkings.knk.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Body of {@code PUT api/road-tiles/{world}/{x}/{z}/graph} (knk-web-api's RoadTileGraphUpsertDto). */
public record RoadTileGraphUpsertDto(
    @JsonProperty("builderVersion") int builderVersion,
    @JsonProperty("cellCount") int cellCount,
    @JsonProperty("levelCount") int levelCount,
    @JsonProperty("warnings") List<String> warnings,
    @JsonProperty("nodes") List<RoadTileGraphNodeDto> nodes,
    @JsonProperty("edges") List<RoadTileGraphEdgeDto> edges
) {}

package net.knightsandkings.knk.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Body of {@code PUT api/road-tiles/{world}/{x}/{z}/proposal} (knk-web-api's RoadTileProposalUpsertDto, plan §5.7 D5). */
public record RoadTileProposalUpsertDto(
    @JsonProperty("baseVersion") int baseVersion,
    @JsonProperty("builderVersion") int builderVersion,
    @JsonProperty("createdBy") String createdBy,
    @JsonProperty("cellCount") int cellCount,
    @JsonProperty("levelCount") int levelCount,
    @JsonProperty("warnings") List<String> warnings,
    @JsonProperty("items") List<RoadProposalItemDto> items,
    @JsonProperty("rejected") List<RoadProposalItemDto> rejected,
    @JsonProperty("addedCount") int addedCount,
    @JsonProperty("removedCount") int removedCount,
    @JsonProperty("changedCount") int changedCount,
    @JsonProperty("movedCount") int movedCount
) {}

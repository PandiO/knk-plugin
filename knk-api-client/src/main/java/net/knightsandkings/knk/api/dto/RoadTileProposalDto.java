package net.knightsandkings.knk.api.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import net.knightsandkings.knk.api.serialization.LenientOffsetDateTimeDeserializer;

/**
 * knk-web-api's RoadTileProposalDto and RoadTileProposalSummaryDto (plan §5.7 D5): the list route
 * leaves out {@code cellCount}, {@code levelCount}, {@code warnings}, {@code items} and {@code rejected}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RoadTileProposalDto(
    @JsonProperty("tileId") int tileId,
    @JsonProperty("world") String world,
    @JsonProperty("tileX") int tileX,
    @JsonProperty("tileZ") int tileZ,
    @JsonProperty("baseVersion") int baseVersion,
    @JsonProperty("tileVersion") int tileVersion,
    @JsonProperty("builderVersion") int builderVersion,
    @JsonProperty("createdBy") String createdBy,
    @JsonProperty("createdAt") @JsonDeserialize(using = LenientOffsetDateTimeDeserializer.class) OffsetDateTime createdAt,
    @JsonProperty("updatedAt") @JsonDeserialize(using = LenientOffsetDateTimeDeserializer.class) OffsetDateTime updatedAt,
    @JsonProperty("addedCount") int addedCount,
    @JsonProperty("removedCount") int removedCount,
    @JsonProperty("changedCount") int changedCount,
    @JsonProperty("movedCount") int movedCount,
    @JsonProperty("rejectedCount") int rejectedCount,
    @JsonProperty("cellCount") int cellCount,
    @JsonProperty("levelCount") int levelCount,
    @JsonProperty("warnings") List<String> warnings,
    @JsonProperty("items") List<RoadProposalItemDto> items,
    @JsonProperty("rejected") List<RoadProposalItemDto> rejected
) {}

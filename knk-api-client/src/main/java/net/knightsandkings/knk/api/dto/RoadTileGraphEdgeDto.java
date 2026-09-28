package net.knightsandkings.knk.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * An edge of the build upload (knk-web-api's RoadTileGraphEdgeDto). {@code fromKey}/{@code toKey}
 * name payload nodes (or {@code id:<n>} for an existing node); {@code geometry} is
 * {@code [[x, y, z], …]}; {@code existingId}/{@code profileId} are left out when absent.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RoadTileGraphEdgeDto(
    @JsonProperty("existingId") Integer existingId,
    @JsonProperty("fromKey") String fromKey,
    @JsonProperty("toKey") String toKey,
    @JsonProperty("geometry") int[][] geometry,
    @JsonProperty("length") double length,
    @JsonProperty("avgWidth") double avgWidth,
    @JsonProperty("profileId") Integer profileId,
    @JsonProperty("gateDoorIds") List<Integer> gateDoorIds,
    @JsonProperty("domainIds") List<Integer> domainIds,
    @JsonProperty("regionIds") List<String> regionIds
) {}

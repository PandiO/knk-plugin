package net.knightsandkings.knk.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Maps to knk-web-api's RoadEdgeDto. {@code geometry} is {@code [[x, y, z], …]} floor blocks;
 * {@code flags} is a string array ({@code Oneway}, {@code NoGps}, {@code Closed}); {@code streetSource}
 * ({@code Inferred | Manual | None}), {@code source} ({@code Detected | Recorded | Stitch}) and
 * {@code status} ({@code Ok | Stale}) are enum names.
 */
public record RoadEdgeDto(
    @JsonProperty("id") int id,
    @JsonProperty("fromNodeId") int fromNodeId,
    @JsonProperty("toNodeId") int toNodeId,
    @JsonProperty("tileId") int tileId,
    @JsonProperty("world") String world,
    @JsonProperty("geometry") int[][] geometry,
    @JsonProperty("length") double length,
    @JsonProperty("minX") int minX,
    @JsonProperty("minY") int minY,
    @JsonProperty("minZ") int minZ,
    @JsonProperty("maxX") int maxX,
    @JsonProperty("maxY") int maxY,
    @JsonProperty("maxZ") int maxZ,
    @JsonProperty("avgWidth") double avgWidth,
    @JsonProperty("profileId") Integer profileId,
    @JsonProperty("streetId") Integer streetId,
    @JsonProperty("streetSource") String streetSource,
    @JsonProperty("costMultiplier") double costMultiplier,
    @JsonProperty("flags") List<String> flags,
    @JsonProperty("gateDoorIds") List<Integer> gateDoorIds,
    @JsonProperty("domainIds") List<Integer> domainIds,
    @JsonProperty("regionIds") List<String> regionIds,
    @JsonProperty("source") String source,
    @JsonProperty("status") String status
) {}

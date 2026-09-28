package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Maps to knk-web-api's RoadNodeDto. {@code kind} ({@code Junction | Endpoint | Boundary | Anchor})
 * and {@code source} ({@code Detected | Manual}) are enum names.
 */
public record RoadNodeDto(
    @JsonProperty("id") int id,
    @JsonProperty("world") String world,
    @JsonProperty("x") int x,
    @JsonProperty("y") int y,
    @JsonProperty("z") int z,
    @JsonProperty("tileId") int tileId,
    @JsonProperty("kind") String kind,
    @JsonProperty("source") String source,
    @JsonProperty("name") String name,
    @JsonProperty("componentId") int componentId,
    @JsonProperty("locked") boolean locked
) {}

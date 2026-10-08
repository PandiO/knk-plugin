package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A node of the build upload (knk-web-api's RoadTileGraphNodeDto). {@code key} is a client-chosen
 * string unique within the payload (never starting with {@code id:}); {@code existingId} only for a
 * node of this tile, left out when absent.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RoadTileGraphNodeDto(
    @JsonProperty("key") String key,
    @JsonProperty("existingId") Integer existingId,
    @JsonProperty("x") int x,
    @JsonProperty("y") int y,
    @JsonProperty("z") int z,
    @JsonProperty("kind") String kind
) {}

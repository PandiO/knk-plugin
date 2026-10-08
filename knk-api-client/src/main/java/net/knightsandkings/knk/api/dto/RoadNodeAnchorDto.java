package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Body of {@code POST api/road-nodes/anchor} (knk-web-api's RoadNodeAnchorDto). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RoadNodeAnchorDto(
    @JsonProperty("world") String world,
    @JsonProperty("x") int x,
    @JsonProperty("y") int y,
    @JsonProperty("z") int z,
    @JsonProperty("name") String name
) {}

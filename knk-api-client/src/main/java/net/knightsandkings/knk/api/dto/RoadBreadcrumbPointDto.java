package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Maps to knk-web-api's RoadBreadcrumbPointDto (a survey walk's point). */
public record RoadBreadcrumbPointDto(
    @JsonProperty("x") int x,
    @JsonProperty("y") int y,
    @JsonProperty("z") int z,
    @JsonProperty("onRoad") boolean onRoad
) {}

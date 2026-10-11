package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Body of {@code POST api/road-seeds} (knk-web-api's RoadSeedCreateDto). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RoadSeedCreateDto(
    @JsonProperty("world") String world,
    @JsonProperty("x") int x,
    @JsonProperty("y") int y,
    @JsonProperty("z") int z,
    @JsonProperty("source") String source,
    @JsonProperty("surveyId") Integer surveyId,
    @JsonProperty("note") String note
) {}

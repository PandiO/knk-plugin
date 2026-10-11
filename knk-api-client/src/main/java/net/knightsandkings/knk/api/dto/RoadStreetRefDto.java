package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Maps to knk-web-api's RoadStreetRefDto (a street some edge is labelled with). */
public record RoadStreetRefDto(
    @JsonProperty("id") int id,
    @JsonProperty("name") String name
) {}

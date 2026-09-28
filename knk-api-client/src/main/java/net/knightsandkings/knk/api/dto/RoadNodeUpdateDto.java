package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Body of {@code PUT api/road-nodes/{id}} (knk-web-api's RoadNodeUpdateDto); nulls are left out = unchanged. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RoadNodeUpdateDto(
    @JsonProperty("name") String name,
    @JsonProperty("clearName") boolean clearName,
    @JsonProperty("kind") String kind,
    @JsonProperty("locked") Boolean locked
) {}

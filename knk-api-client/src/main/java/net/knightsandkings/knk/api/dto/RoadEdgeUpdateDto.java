package net.knightsandkings.knk.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Body of {@code PUT api/road-edges/{id}} (knk-web-api's RoadEdgeUpdateDto); nulls are left out =
 * unchanged, the {@code clear…} flags clear (Phase 1 decision 11).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RoadEdgeUpdateDto(
    @JsonProperty("streetId") Integer streetId,
    @JsonProperty("clearStreet") boolean clearStreet,
    @JsonProperty("propagate") boolean propagate,
    @JsonProperty("profileId") Integer profileId,
    @JsonProperty("clearProfile") boolean clearProfile,
    @JsonProperty("costMultiplier") Double costMultiplier,
    @JsonProperty("flags") List<String> flags
) {}

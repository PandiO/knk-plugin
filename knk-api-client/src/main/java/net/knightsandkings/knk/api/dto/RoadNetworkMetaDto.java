package net.knightsandkings.knk.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Maps to knk-web-api's RoadNetworkMetaDto ({@code GET api/road-network/meta?world=}). */
public record RoadNetworkMetaDto(
    @JsonProperty("profiles") List<RoadProfileDto> profiles,
    @JsonProperty("streets") List<RoadStreetRefDto> streets,
    @JsonProperty("components") List<RoadComponentDto> components
) {}

package net.knightsandkings.knk.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Body of {@code POST api/road-profiles} / {@code PUT api/road-profiles/{id}} (knk-web-api's
 * RoadProfileUpsertDto). A {@code null} {@code stats} is left out, which keeps the stored stats on a
 * PUT (plan D5).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RoadProfileUpsertDto(
    @JsonProperty("name") String name,
    @JsonProperty("roadClass") String roadClass,
    @JsonProperty("costMultiplier") double costMultiplier,
    @JsonProperty("materials") List<RoadMaterialDto> materials,
    @JsonProperty("widthMin") int widthMin,
    @JsonProperty("widthMax") int widthMax,
    @JsonProperty("sampleCount") int sampleCount,
    @JsonProperty("enabled") boolean enabled,
    @JsonProperty("scopeTownIds") List<Integer> scopeTownIds,
    @JsonProperty("stats") JsonNode stats
) {}

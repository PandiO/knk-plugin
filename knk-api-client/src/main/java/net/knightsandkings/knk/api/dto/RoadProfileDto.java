package net.knightsandkings.knk.api.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import net.knightsandkings.knk.api.serialization.LenientOffsetDateTimeDeserializer;

/**
 * Maps to knk-web-api's RoadProfileDto (road navigation, KNG-27). {@code roadClass} is the enum name;
 * {@code stats} is the opaque accumulated survey statistics object (plan D5), {@code null} when none.
 */
public record RoadProfileDto(
    @JsonProperty("id") int id,
    @JsonProperty("name") String name,
    @JsonProperty("roadClass") String roadClass,
    @JsonProperty("costMultiplier") double costMultiplier,
    @JsonProperty("materials") List<RoadMaterialDto> materials,
    @JsonProperty("widthMin") int widthMin,
    @JsonProperty("widthMax") int widthMax,
    @JsonProperty("sampleCount") int sampleCount,
    @JsonProperty("enabled") boolean enabled,
    @JsonProperty("scopeTownIds") List<Integer> scopeTownIds,
    @JsonProperty("stats") JsonNode stats,
    @JsonProperty("createdAt") @JsonDeserialize(using = LenientOffsetDateTimeDeserializer.class) OffsetDateTime createdAt,
    @JsonProperty("updatedAt") @JsonDeserialize(using = LenientOffsetDateTimeDeserializer.class) OffsetDateTime updatedAt
) {}

package net.knightsandkings.knk.api.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import net.knightsandkings.knk.api.serialization.LenientOffsetDateTimeDeserializer;

/** Maps to knk-web-api's RoadSeedDto. {@code source} is the enum name ({@code Admin | Survey}). */
public record RoadSeedDto(
    @JsonProperty("id") int id,
    @JsonProperty("world") String world,
    @JsonProperty("x") int x,
    @JsonProperty("y") int y,
    @JsonProperty("z") int z,
    @JsonProperty("source") String source,
    @JsonProperty("surveyId") Integer surveyId,
    @JsonProperty("note") String note,
    @JsonProperty("createdAt") @JsonDeserialize(using = LenientOffsetDateTimeDeserializer.class) OffsetDateTime createdAt
) {}

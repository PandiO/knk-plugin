package net.knightsandkings.knk.api.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import net.knightsandkings.knk.api.serialization.LenientOffsetDateTimeDeserializer;

/** Maps to knk-web-api's RoadSurveyDto (a stored survey walk). */
public record RoadSurveyDto(
    @JsonProperty("id") int id,
    @JsonProperty("world") String world,
    @JsonProperty("profileId") Integer profileId,
    @JsonProperty("startedByUserId") Integer startedByUserId,
    @JsonProperty("startedAt") @JsonDeserialize(using = LenientOffsetDateTimeDeserializer.class) OffsetDateTime startedAt,
    @JsonProperty("endedAt") @JsonDeserialize(using = LenientOffsetDateTimeDeserializer.class) OffsetDateTime endedAt,
    @JsonProperty("sampleCount") int sampleCount,
    @JsonProperty("breadcrumb") List<RoadBreadcrumbPointDto> breadcrumb,
    @JsonProperty("stats") JsonNode stats
) {}

package net.knightsandkings.knk.api.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Body of {@code POST api/road-surveys} (knk-web-api's RoadSurveyCreateDto). Dates go as ISO-8601
 * strings (the client's ObjectMapper would otherwise write them as numeric timestamps, which
 * ASP.NET rejects).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RoadSurveyCreateDto(
    @JsonProperty("world") String world,
    @JsonProperty("profileId") Integer profileId,
    @JsonProperty("startedAt") @JsonFormat(shape = JsonFormat.Shape.STRING) OffsetDateTime startedAt,
    @JsonProperty("endedAt") @JsonFormat(shape = JsonFormat.Shape.STRING) OffsetDateTime endedAt,
    @JsonProperty("sampleCount") int sampleCount,
    @JsonProperty("breadcrumb") List<RoadBreadcrumbPointDto> breadcrumb,
    @JsonProperty("stats") JsonNode stats
) {}

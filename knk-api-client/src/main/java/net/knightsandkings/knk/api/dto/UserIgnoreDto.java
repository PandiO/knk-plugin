package net.knightsandkings.knk.api.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Maps to UserIgnoreDto from knk-web-api - GET /api/users/{id}/ignores. */
public record UserIgnoreDto(
    @JsonProperty("ignoredUserId") int ignoredUserId,
    @JsonProperty("ignoredUsername") String ignoredUsername,
    @JsonProperty("ignoredUuid") String ignoredUuid,
    @JsonProperty("createdAt") OffsetDateTime createdAt
) {}

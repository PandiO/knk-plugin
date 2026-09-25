package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO for adjusting a user's coins/gems/experience by a signed delta.
 * Maps to AdjustBalancesDto from knk-web-api - PUT /api/Users/{id}/balances.
 */
public record AdjustBalancesDto(
    @JsonProperty("coinsDelta") int coinsDelta,
    @JsonProperty("gemsDelta") int gemsDelta,
    @JsonProperty("experienceDelta") int experienceDelta,
    @JsonProperty("reason") String reason
) {}

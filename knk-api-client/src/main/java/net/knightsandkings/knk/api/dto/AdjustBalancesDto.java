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
    @JsonProperty("reason") String reason,
    // false = the caller shows any resulting title change in-game itself, so the API must not
    // also queue it for PlayerNotificationPoller (which would show it a second time)
    @JsonProperty("notifyPlayer") boolean notifyPlayer
) {}

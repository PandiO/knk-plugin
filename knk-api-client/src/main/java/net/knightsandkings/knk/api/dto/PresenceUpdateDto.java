package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO for reporting a player's online presence.
 * Maps to UpdatePresenceDto from knk-web-api (docs/specs/user-management/IMPLEMENTATION_PLAN.md
 * Phase 3) - PUT /api/Users/{id}/presence.
 */
public record PresenceUpdateDto(
    @JsonProperty("isOnline") boolean isOnline
) {}

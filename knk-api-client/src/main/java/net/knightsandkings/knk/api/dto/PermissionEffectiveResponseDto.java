package net.knightsandkings.knk.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response DTO for GET /api/users/{id}/permissions/effective.
 */
public record PermissionEffectiveResponseDto(
    @JsonProperty("userId") Integer userId,
    @JsonProperty("permissions") List<EffectivePermissionEntryDto> permissions
) {
}

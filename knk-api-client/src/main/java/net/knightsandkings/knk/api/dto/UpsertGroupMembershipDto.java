package net.knightsandkings.knk.api.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Maps to knk-web-api's UpsertUserPermissionGroupDto - PUT /api/UserPermissionGroups.
 */
public record UpsertGroupMembershipDto(
    @JsonProperty("userId") int userId,
    @JsonProperty("permissionGroupId") int permissionGroupId,
    @JsonProperty("expiresAt") OffsetDateTime expiresAt
) {}

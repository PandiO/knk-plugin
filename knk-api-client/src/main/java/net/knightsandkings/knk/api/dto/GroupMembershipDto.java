package net.knightsandkings.knk.api.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Maps to knk-web-api's UserPermissionGroupDto - GET /api/UserPermissionGroups?userId=.
 */
public record GroupMembershipDto(
    @JsonProperty("userId") int userId,
    @JsonProperty("permissionGroupId") int permissionGroupId,
    @JsonProperty("permissionGroupName") String permissionGroupName,
    @JsonProperty("weight") int weight,
    @JsonProperty("isPremiumTier") boolean isPremiumTier,
    @JsonProperty("expiresAt") OffsetDateTime expiresAt,
    @JsonProperty("isActive") boolean isActive
) {}

package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Maps to knk-web-api's PermissionGroupDto - GET /api/PermissionGroups. Only the fields
 * PermissionGroupsQueryApi.list() callers need.
 */
public record PermissionGroupListItemDto(
    @JsonProperty("id") Integer id,
    @JsonProperty("name") String name,
    @JsonProperty("weight") int weight,
    @JsonProperty("isPremiumTier") boolean isPremiumTier
) {}

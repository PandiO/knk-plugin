package net.knightsandkings.knk.api.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Maps to knk-web-api's UpsertPermissionGrantByNodeDto - PUT /api/PermissionGrants/by-node.
 */
public record UpsertPermissionGrantByNodeDto(
    @JsonProperty("holderId") int holderId,
    @JsonProperty("node") String node,
    @JsonProperty("value") boolean value,
    @JsonProperty("expiresAt") OffsetDateTime expiresAt
) {}

package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response DTO for GET /api/users/{id}/permissions/check.
 */
public record PermissionCheckResponseDto(
    @JsonProperty("userId") Integer userId,
    @JsonProperty("node") String node,
    @JsonProperty("result") String result, // "Granted" | "Denied" | "Undeclared"
    @JsonProperty("allowed") boolean allowed,
    @JsonProperty("sourceHolderId") Integer sourceHolderId,
    @JsonProperty("sourceHolderType") String sourceHolderType,
    @JsonProperty("matchedNode") String matchedNode
) {
}

package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One entry within a {@link PermissionEffectiveResponseDto}.
 */
public record EffectivePermissionEntryDto(
    @JsonProperty("node") String node,
    @JsonProperty("value") boolean value,
    @JsonProperty("sourceHolderId") Integer sourceHolderId,
    @JsonProperty("sourceHolderType") String sourceHolderType,
    @JsonProperty("sourceHolderName") String sourceHolderName
) {
}

package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Maps to RewardMultiplierDto from knk-web-api: one multiplier applied to a salary payout or a
 * title promotion bonus (KNG-16).
 */
public record RewardMultiplierDto(
    @JsonProperty("source") String source,
    @JsonProperty("value") double value,
    @JsonProperty("permissionGroupId") Integer permissionGroupId,
    @JsonProperty("name") String name,
    @JsonProperty("isPremiumTier") boolean isPremiumTier,
    @JsonProperty("chatPrimaryColor") String chatPrimaryColor,
    @JsonProperty("chatSecondaryColor") String chatSecondaryColor
) {}

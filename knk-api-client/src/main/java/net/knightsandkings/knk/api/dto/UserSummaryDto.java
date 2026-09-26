package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UserSummaryDto (
    @JsonProperty("id") Integer id,
    @JsonProperty("username") String username,
    @JsonProperty("uuid") java.util.UUID uuid,
    @JsonProperty("email") String email,
    @JsonProperty("coins") int coins,
    @JsonProperty("gems") int gems,
    @JsonProperty("experiencePoints") int experiencePoints,
    @JsonProperty("isFullAccount") boolean isFullAccount,
    @JsonProperty("gatePassThroughMethodDefault") String gatePassThroughMethodDefault,
    @JsonProperty("activeMode") String activeMode,
    @JsonProperty("titleBracketId") Integer titleBracketId,
    @JsonProperty("titleName") String titleName,
    @JsonProperty("prestigeExperience") int prestigeExperience,
    @JsonProperty("premiumTierGroupId") Integer premiumTierGroupId,
    @JsonProperty("premiumTierName") String premiumTierName,
    @JsonProperty("premiumTierExpiresAt") java.time.OffsetDateTime premiumTierExpiresAt,
    @JsonProperty("isFrozen") boolean isFrozen,
    @JsonProperty("frozenReason") String frozenReason,
    // "Male" / "Female" / null (InventoryMenu content port CP3).
    @JsonProperty("gender") String gender
) {}

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
    @JsonProperty("gender") String gender,
    // KNG-7 display styles (Minecraft "&" formatting codes), resolved server-side from the premium tier
    // with the Default group as fallback.
    @JsonProperty("chatPrimaryColor") String chatPrimaryColor,
    @JsonProperty("chatSecondaryColor") String chatSecondaryColor,
    @JsonProperty("nameColor") String nameColor,
    // KNG-52: effective groups in Game Settings precedence order.
    @JsonProperty("permissionGroups") java.util.List<PermissionGroupRefDto> permissionGroups
) {
    /** Without the KNG-52 group list. */
    public UserSummaryDto(Integer id, String username, java.util.UUID uuid, String email, int coins, int gems,
                          int experiencePoints, boolean isFullAccount, String gatePassThroughMethodDefault,
                          String activeMode, Integer titleBracketId, String titleName, int prestigeExperience,
                          Integer premiumTierGroupId, String premiumTierName,
                          java.time.OffsetDateTime premiumTierExpiresAt, boolean isFrozen, String frozenReason,
                          String gender, String chatPrimaryColor, String chatSecondaryColor, String nameColor) {
        this(id, username, uuid, email, coins, gems, experiencePoints, isFullAccount, gatePassThroughMethodDefault,
            activeMode, titleBracketId, titleName, prestigeExperience, premiumTierGroupId, premiumTierName,
            premiumTierExpiresAt, isFrozen, frozenReason, gender, chatPrimaryColor, chatSecondaryColor, nameColor, null);
    }
}

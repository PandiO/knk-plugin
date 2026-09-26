package net.knightsandkings.knk.core.domain.users;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public record UserSummary(
    Integer id,
    String username,
    UUID uuid,
    String email,
    int coins,
    int gems,
    int experiencePoints,
    boolean isFullAccount,
    boolean isNewUser,
    GatePassThroughMethod gatePassThroughMethodDefault,
    ActiveMode activeMode,
    Integer titleBracketId,
    String titleName,
    int prestigeExperience,
    Integer premiumTierGroupId,
    String premiumTierName,
    OffsetDateTime premiumTierExpiresAt,
    boolean isFrozen,
    String frozenReason,
    // "Male", "Female" or null (unset - title names fall back to the male name). Added for the
    // InventoryMenu Profile menu (docs/specs/inventory-menu/CONTENT_PORT_PLAN.md CP3).
    String gender,
    // Chat and tab-list styles (KNG-7) as Minecraft "&" formatting codes ("&e", "&6&l", hex
    // "&x&f&f&a&a&0&0"): the premium tier's, else the Default PermissionGroup's, resolved
    // server-side. Primary = title + username, secondary = the "-{ }-" brackets, name =
    // scoreboard team color. Null = the plugin's built-in default.
    String chatPrimaryColor,
    String chatSecondaryColor,
    String nameColor
) {
    public UserSummary {
        if (activeMode == null) {
            activeMode = ActiveMode.NONE;
        }
    }

    // Constructor without the KNG-7 display colors - null (plugin defaults).
    public UserSummary(Integer id, String username, UUID uuid, String email, int coins, int gems, int experiencePoints, boolean isFullAccount, boolean isNewUser, GatePassThroughMethod gatePassThroughMethodDefault, ActiveMode activeMode, Integer titleBracketId, String titleName, int prestigeExperience, Integer premiumTierGroupId, String premiumTierName, OffsetDateTime premiumTierExpiresAt, boolean isFrozen, String frozenReason, String gender) {
        this(id, username, uuid, email, coins, gems, experiencePoints, isFullAccount, isNewUser, gatePassThroughMethodDefault, activeMode, titleBracketId, titleName, prestigeExperience, premiumTierGroupId, premiumTierName, premiumTierExpiresAt, isFrozen, frozenReason, gender, null, null, null);
    }

    // Constructor without gender - null (unset). Kept so the existing call sites stay as they were.
    public UserSummary(Integer id, String username, UUID uuid, String email, int coins, int gems, int experiencePoints, boolean isFullAccount, boolean isNewUser, GatePassThroughMethod gatePassThroughMethodDefault, ActiveMode activeMode, Integer titleBracketId, String titleName, int prestigeExperience, Integer premiumTierGroupId, String premiumTierName, OffsetDateTime premiumTierExpiresAt, boolean isFrozen, String frozenReason) {
        this(id, username, uuid, email, coins, gems, experiencePoints, isFullAccount, isNewUser, gatePassThroughMethodDefault, activeMode, titleBracketId, titleName, prestigeExperience, premiumTierGroupId, premiumTierName, premiumTierExpiresAt, isFrozen, frozenReason, null);
    }

    // Constructor without freeze fields - defaults to not frozen. Kept alongside the shorter
    // legacy constructors below rather than threading isFrozen/frozenReason through every one of
    // them (this record already has 5 telescoping constructors for older call sites).
    public UserSummary(Integer id, String username, UUID uuid, String email, int coins, int gems, int experiencePoints, boolean isFullAccount, boolean isNewUser, GatePassThroughMethod gatePassThroughMethodDefault, ActiveMode activeMode, Integer titleBracketId, String titleName, int prestigeExperience, Integer premiumTierGroupId, String premiumTierName, OffsetDateTime premiumTierExpiresAt) {
        this(id, username, uuid, email, coins, gems, experiencePoints, isFullAccount, isNewUser, gatePassThroughMethodDefault, activeMode, titleBracketId, titleName, prestigeExperience, premiumTierGroupId, premiumTierName, premiumTierExpiresAt, false, null);
    }

    // Constructor without premium tier fields - resolved server-side from the user's premium
    // PermissionGroup memberships (docs/specs/user-features/IMPLEMENTATION_PLAN.md §5); null
    // until a real fetch fills them, and null afterwards if the user holds no premium tier.
    public UserSummary(Integer id, String username, UUID uuid, String email, int coins, int gems, int experiencePoints, boolean isFullAccount, boolean isNewUser, GatePassThroughMethod gatePassThroughMethodDefault, ActiveMode activeMode, Integer titleBracketId, String titleName, int prestigeExperience) {
        this(id, username, uuid, email, coins, gems, experiencePoints, isFullAccount, isNewUser, gatePassThroughMethodDefault, activeMode, titleBracketId, titleName, prestigeExperience, null, null, null);
    }

    // Constructor without title fields - resolved server-side from experiencePoints
    // (docs/specs/user-features/IMPLEMENTATION_PLAN.md §4); null/0 until a real fetch fills them.
    public UserSummary(Integer id, String username, UUID uuid, String email, int coins, int gems, int experiencePoints, boolean isFullAccount, boolean isNewUser, GatePassThroughMethod gatePassThroughMethodDefault, ActiveMode activeMode) {
        this(id, username, uuid, email, coins, gems, experiencePoints, isFullAccount, isNewUser, gatePassThroughMethodDefault, activeMode, null, null, 0);
    }

    // Constructor without activeMode - defaults to NONE
    public UserSummary(Integer id, String username, UUID uuid, String email, int coins, int gems, int experiencePoints, boolean isFullAccount, boolean isNewUser, GatePassThroughMethod gatePassThroughMethodDefault) {
        this(id, username, uuid, email, coins, gems, experiencePoints, isFullAccount, isNewUser, gatePassThroughMethodDefault, ActiveMode.NONE, null, null, 0);
    }

    // Constructor without isNewUser/gatePassThroughMethodDefault - defaults to false/DEFAULT
    public UserSummary(Integer id, String username, UUID uuid, String email, int coins, int gems, int experiencePoints, boolean isFullAccount) {
        this(id, username, uuid, email, coins, gems, experiencePoints, isFullAccount, false, GatePassThroughMethod.DEFAULT, ActiveMode.NONE, null, null, 0);
    }

    // Legacy constructor for backwards compatibility (minimal user data)
    public UserSummary(Integer id, String username, UUID uuid, int coins) {
        this(id, username, uuid, null, coins, 0, 0, false, false, GatePassThroughMethod.DEFAULT, ActiveMode.NONE, null, null, 0);
    }

    /**
     * Copy with an updated owner/staff mode (after /ownermode or /staffmode).
     */
    public UserSummary withActiveMode(ActiveMode activeMode) {
        return new UserSummary(id, username, uuid, email, coins, gems, experiencePoints, isFullAccount, isNewUser, gatePassThroughMethodDefault, activeMode, titleBracketId, titleName, prestigeExperience, premiumTierGroupId, premiumTierName, premiumTierExpiresAt, isFrozen, frozenReason, gender, chatPrimaryColor, chatSecondaryColor, nameColor);
    }

    /**
     * Copy with an updated admin-freeze state (after /freeze or /unfreeze) - used by
     * AdminFreezeManager to keep the cached UserSummary consistent with the join-time restore.
     */
    public UserSummary withFrozen(boolean isFrozen, String frozenReason) {
        return new UserSummary(id, username, uuid, email, coins, gems, experiencePoints, isFullAccount, isNewUser, gatePassThroughMethodDefault, activeMode, titleBracketId, titleName, prestigeExperience, premiumTierGroupId, premiumTierName, premiumTierExpiresAt, isFrozen, frozenReason, gender, chatPrimaryColor, chatSecondaryColor, nameColor);
    }
}

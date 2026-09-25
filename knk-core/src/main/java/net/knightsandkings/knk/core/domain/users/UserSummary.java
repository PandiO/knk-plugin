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
    OffsetDateTime premiumTierExpiresAt
) {
    public UserSummary {
        if (activeMode == null) {
            activeMode = ActiveMode.NONE;
        }
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
        return new UserSummary(id, username, uuid, email, coins, gems, experiencePoints, isFullAccount, isNewUser, gatePassThroughMethodDefault, activeMode, titleBracketId, titleName, prestigeExperience, premiumTierGroupId, premiumTierName, premiumTierExpiresAt);
    }
}

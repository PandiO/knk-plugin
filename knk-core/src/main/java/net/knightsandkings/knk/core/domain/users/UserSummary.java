package net.knightsandkings.knk.core.domain.users;

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
    ActiveMode activeMode
) {
    public UserSummary {
        if (activeMode == null) {
            activeMode = ActiveMode.NONE;
        }
    }

    // Constructor without activeMode - defaults to NONE
    public UserSummary(Integer id, String username, UUID uuid, String email, int coins, int gems, int experiencePoints, boolean isFullAccount, boolean isNewUser, GatePassThroughMethod gatePassThroughMethodDefault) {
        this(id, username, uuid, email, coins, gems, experiencePoints, isFullAccount, isNewUser, gatePassThroughMethodDefault, ActiveMode.NONE);
    }

    // Constructor without isNewUser/gatePassThroughMethodDefault - defaults to false/DEFAULT
    public UserSummary(Integer id, String username, UUID uuid, String email, int coins, int gems, int experiencePoints, boolean isFullAccount) {
        this(id, username, uuid, email, coins, gems, experiencePoints, isFullAccount, false, GatePassThroughMethod.DEFAULT, ActiveMode.NONE);
    }

    // Legacy constructor for backwards compatibility (minimal user data)
    public UserSummary(Integer id, String username, UUID uuid, int coins) {
        this(id, username, uuid, null, coins, 0, 0, false, false, GatePassThroughMethod.DEFAULT, ActiveMode.NONE);
    }

    /**
     * Copy with an updated owner/staff mode (after /ownermode or /staffmode).
     */
    public UserSummary withActiveMode(ActiveMode activeMode) {
        return new UserSummary(id, username, uuid, email, coins, gems, experiencePoints, isFullAccount, isNewUser, gatePassThroughMethodDefault, activeMode);
    }
}

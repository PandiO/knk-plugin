package net.knightsandkings.knk.core.domain.users;

/**
 * One multiplier applied to a reward - a salary payout or a title promotion bonus (KNG-16) - so
 * the player can be told what was multiplied and why. Mirrors knk-web-api's RewardMultiplierDto.
 *
 * @param source           {@link #SOURCE_GLOBAL}, {@link #SOURCE_PERSONAL} or {@link #SOURCE_RANK}
 * @param name             rank only: the permission group's name
 * @param chatPrimaryColor rank only: the group's "&amp;" color codes (KNG-7), as chat shows it
 */
public record RewardMultiplier(
    String source,
    double value,
    Integer permissionGroupId,
    String name,
    boolean isPremiumTier,
    String chatPrimaryColor,
    String chatSecondaryColor
) {
    public static final String SOURCE_GLOBAL = "global";
    public static final String SOURCE_PERSONAL = "personal";
    public static final String SOURCE_RANK = "rank";
}

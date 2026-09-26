package net.knightsandkings.knk.paper.user;

import net.knightsandkings.knk.core.domain.permissions.PermissionGroupSummary;
import net.knightsandkings.knk.core.domain.users.GroupMembershipSummary;

import java.util.Collection;
import java.util.Optional;

/**
 * A player's rank: the free "Default" group or one of the paid premium tiers (Noble, Royal,
 * Dragon Blood), which upgrade from it. The Player manager keeps a player on exactly one of them.
 * Default is recognised by name, the same way knk-web-api's UserService.DefaultGroupName finds it
 * (it's an ordinary group, not flagged IsPremiumTier).
 */
public final class PlayerRanks {
    public static final String DEFAULT_RANK_NAME = "Default";

    private PlayerRanks() {}

    public static boolean isDefault(PermissionGroupSummary group) {
        return group != null && !group.isPremiumTier() && DEFAULT_RANK_NAME.equalsIgnoreCase(group.name());
    }

    public static boolean isRank(PermissionGroupSummary group) {
        return group != null && (group.isPremiumTier() || isDefault(group));
    }

    public static boolean isRank(GroupMembershipSummary membership) {
        return membership.isPremiumTier() || DEFAULT_RANK_NAME.equalsIgnoreCase(membership.groupName());
    }

    public static Optional<PermissionGroupSummary> findDefault(Collection<PermissionGroupSummary> groups) {
        return groups.stream().filter(PlayerRanks::isDefault).findFirst();
    }
}

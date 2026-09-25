package net.knightsandkings.knk.core.domain.users;

import java.time.OffsetDateTime;

/**
 * One of a user's current PermissionGroup memberships (docs/specs/user-features/DESIGN.md §2.1
 * multi-membership model - a user can hold several groups at once, each with its own optional
 * expiry). Mirrors knk-web-api's UserPermissionGroupDto. Used by /knk user group add|remove and
 * by RankHierarchy's weight comparison.
 */
public record GroupMembershipSummary(
    int groupId,
    String groupName,
    int weight,
    boolean isPremiumTier,
    OffsetDateTime expiresAt,
    boolean isActive
) {}

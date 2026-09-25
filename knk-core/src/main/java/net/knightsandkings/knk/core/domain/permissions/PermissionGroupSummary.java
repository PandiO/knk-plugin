package net.knightsandkings.knk.core.domain.permissions;

/**
 * One PermissionGroup as listed by GET /api/PermissionGroups - used for name-to-id resolution
 * (e.g. /knk user &lt;player&gt; group add Royal) and tab-completion. See
 * docs/specs/user-features/DESIGN.md §2.1.
 */
public record PermissionGroupSummary(
    int id,
    String name,
    int weight,
    boolean isPremiumTier
) {}

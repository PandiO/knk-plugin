package net.knightsandkings.knk.core.domain.permissions;

import net.knightsandkings.knk.core.roads.route.RouteMode;

/**
 * One PermissionGroup as listed by GET /api/PermissionGroups - used for name-to-id resolution
 * (e.g. /knk user &lt;player&gt; group add Royal) and tab-completion. See
 * docs/specs/user-features/DESIGN.md §2.1.
 */
public record PermissionGroupSummary(
    int id,
    String name,
    int weight,
    boolean isPremiumTier,
    // PermissionGroup.SalaryMultiplier (1.0 = none) - shown by the InventoryMenu premium-tier
    // screen (docs/specs/inventory-menu/CONTENT_PORT_PLAN.md CP5).
    double salaryMultiplier,
    // KNG-7 chat/tab-list styles as Minecraft "&" formatting codes (PermissionGroup.ChatPrimaryColor etc.),
    // null when unset.
    String chatPrimaryColor,
    String chatSecondaryColor,
    String nameColor,
    // Off-road L7b (PandiO/knk-workspace#852, decisions 41 and 43): PermissionGroup.DefaultRouteMode, the route mode
    // /navigate uses for the group's members when they type none; null = none (today's default).
    RouteMode defaultRouteMode
) {
    public PermissionGroupSummary(int id, String name, int weight, boolean isPremiumTier, double salaryMultiplier,
                                  String chatPrimaryColor, String chatSecondaryColor, String nameColor) {
        this(id, name, weight, isPremiumTier, salaryMultiplier, chatPrimaryColor, chatSecondaryColor, nameColor, null);
    }

    public PermissionGroupSummary(int id, String name, int weight, boolean isPremiumTier, double salaryMultiplier) {
        this(id, name, weight, isPremiumTier, salaryMultiplier, null, null, null);
    }

    public PermissionGroupSummary(int id, String name, int weight, boolean isPremiumTier) {
        this(id, name, weight, isPremiumTier, 1.0);
    }
}

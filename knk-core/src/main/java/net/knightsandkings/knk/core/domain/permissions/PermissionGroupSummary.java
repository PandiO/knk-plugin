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
    boolean isPremiumTier,
    // PermissionGroup.SalaryMultiplier (1.0 = none) - shown by the InventoryMenu premium-tier
    // screen (docs/specs/inventory-menu/CONTENT_PORT_PLAN.md CP5).
    double salaryMultiplier,
    // KNG-7 chat/tab-list styles as Minecraft "&" formatting codes (PermissionGroup.ChatPrimaryColor etc.),
    // null when unset.
    String chatPrimaryColor,
    String chatSecondaryColor,
    String nameColor
) {
    public PermissionGroupSummary(int id, String name, int weight, boolean isPremiumTier, double salaryMultiplier) {
        this(id, name, weight, isPremiumTier, salaryMultiplier, null, null, null);
    }

    public PermissionGroupSummary(int id, String name, int weight, boolean isPremiumTier) {
        this(id, name, weight, isPremiumTier, 1.0);
    }
}

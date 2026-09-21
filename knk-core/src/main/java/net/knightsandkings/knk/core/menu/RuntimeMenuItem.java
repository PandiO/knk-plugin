package net.knightsandkings.knk.core.menu;

import net.knightsandkings.knk.core.domain.menu.KnkActionBinding;
import net.knightsandkings.knk.core.domain.menu.KnkConditionBinding;
import net.knightsandkings.knk.core.domain.menu.KnkVariableBinding;

import java.util.List;
import java.util.function.Predicate;

/**
 * The rendered-instance side of {@code KnkMenuItemTemplate} (FR-2.1.3):
 * template data with its enum-shaped String fields parsed. Name/lore are
 * intentionally not resolved fields here - they stay as the raw
 * {@code variableBindings} list; Phase 2 leaves them as a literal placeholder
 * (the binding's raw Expression) at render time in knk-paper, and Phase 3's
 * real VariableResolver replaces that step without needing this class to
 * change.
 * <p>
 * {@code slotOverride}, when present, is an absolute slot index in the parent
 * Menu's Inventory (not relative to the owning section) - it pins the item in
 * place, bypassing the section's automatic grid-fill/pagination entirely; see
 * {@link RuntimeMenuSection#resolveSlots}.
 */
public record RuntimeMenuItem(
        Integer id,
        int sortOrder,
        Integer slotOverride,
        Integer materialRefId,
        int amount,
        String chatColorName,
        String chatColorDescription,
        MenuDisplayMode displayMode,
        String visibilityPermission,
        String actionPermission,
        List<KnkVariableBinding> variableBindings,
        List<KnkActionBinding> actions,
        List<KnkConditionBinding> conditions
) {

    /**
     * IMPLEMENTATION_PLAN.md Phase 4 / DESIGN_REVIEW.md §2.4: whether this item
     * should render at all for a player. A null/blank {@link #visibilityPermission}
     * means "no restriction" - independent of {@link #isActionAllowedFor}, since
     * a node can be visible to everyone but actionable only by some (or vice
     * versa). The {@code permissionChecker} is a {@code String -> boolean}
     * function rather than a live Bukkit type so this stays testable without
     * Bukkit on the classpath; knk-paper supplies {@code player::hasPermission}.
     */
    public boolean isVisibleTo(Predicate<String> permissionChecker) {
        return isAllowed(visibilityPermission, permissionChecker);
    }

    /**
     * Whether a click on this item should be allowed to proceed for a player -
     * checked independently of {@link #isVisibleTo}, and re-checked at click
     * time (not just render time) per DESIGN_REVIEW.md §2.4's defense-in-depth
     * requirement: never trust that render-time hiding/disabling alone was
     * sufficient.
     */
    public boolean isActionAllowedFor(Predicate<String> permissionChecker) {
        return isAllowed(actionPermission, permissionChecker);
    }

    private static boolean isAllowed(String permissionNode, Predicate<String> permissionChecker) {
        return permissionNode == null || permissionNode.isBlank() || permissionChecker.test(permissionNode);
    }
}

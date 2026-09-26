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
 * <p>
 * {@code rowTemplate} (InventoryMenu Phase 9, E3): this item is its section's
 * row template - never placed itself; each row the section's content source
 * yields is rendered through it with root {@code $row$}.
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
        List<KnkConditionBinding> conditions,
        boolean rowTemplate
) {

    /** Pre-Phase-9 shape: not a row template. */
    public RuntimeMenuItem(Integer id, int sortOrder, Integer slotOverride, Integer materialRefId, int amount,
                           String chatColorName, String chatColorDescription, MenuDisplayMode displayMode,
                           String visibilityPermission, String actionPermission,
                           List<KnkVariableBinding> variableBindings, List<KnkActionBinding> actions,
                           List<KnkConditionBinding> conditions) {
        this(id, sortOrder, slotOverride, materialRefId, amount, chatColorName, chatColorDescription, displayMode,
                visibilityPermission, actionPermission, variableBindings, actions, conditions, false);
    }

    /**
     * InventoryMenu Phase 9 (E5/E6): the rendered snapshot of this item for one
     * render pass - the effective display mode (a {@code DisplayMode} binding may
     * override the column) and the actions that survived their Render-phase
     * conditions. Stored per slot for click routing, so a click sees exactly
     * what was rendered.
     */
    public RuntimeMenuItem withRenderState(MenuDisplayMode effectiveDisplayMode, List<KnkActionBinding> effectiveActions) {
        if (effectiveDisplayMode == displayMode && effectiveActions.equals(actions)) {
            return this;
        }
        return new RuntimeMenuItem(id, sortOrder, slotOverride, materialRefId, amount, chatColorName,
                chatColorDescription, effectiveDisplayMode, visibilityPermission, actionPermission,
                variableBindings, List.copyOf(effectiveActions), conditions, rowTemplate);
    }

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

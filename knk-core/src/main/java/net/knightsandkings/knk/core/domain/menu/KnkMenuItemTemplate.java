package net.knightsandkings.knk.core.domain.menu;

import java.util.List;

/**
 * A single clickable inventory item within a MenuSectionTemplate (FR-2.1.3).
 * Name/lore are not plain fields here - they come from variableBindings
 * (even a literal, unresolved string is a STATIC-policy binding), per
 * IMPLEMENTATION_PLAN.md's "name and lore as variable-bound strings".
 * <p>
 * {@code rowTemplate} (InventoryMenu Phase 9, E3): this item renders each row
 * its section's content source yields, with root {@code $row$}.
 */
public record KnkMenuItemTemplate(
        Integer id,
        Integer sortOrder,
        Integer slotOverride,
        Integer materialRefId,
        Integer amount,
        String chatColorName,
        String chatColorDescription,
        String displayMode,
        String visibilityPermission,
        String actionPermission,
        List<KnkVariableBinding> variableBindings,
        List<KnkActionBinding> actions,
        List<KnkConditionBinding> conditions,
        Boolean rowTemplate
) {

    /** Pre-Phase-9 shape (no {@code rowTemplate}). */
    public KnkMenuItemTemplate(Integer id, Integer sortOrder, Integer slotOverride, Integer materialRefId, Integer amount,
                               String chatColorName, String chatColorDescription, String displayMode,
                               String visibilityPermission, String actionPermission,
                               List<KnkVariableBinding> variableBindings, List<KnkActionBinding> actions,
                               List<KnkConditionBinding> conditions) {
        this(id, sortOrder, slotOverride, materialRefId, amount, chatColorName, chatColorDescription, displayMode,
                visibilityPermission, actionPermission, variableBindings, actions, conditions, null);
    }
}

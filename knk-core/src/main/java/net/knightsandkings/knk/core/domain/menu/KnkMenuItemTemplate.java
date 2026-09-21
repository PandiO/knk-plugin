package net.knightsandkings.knk.core.domain.menu;

import java.util.List;

/**
 * A single clickable inventory item within a MenuSectionTemplate (FR-2.1.3).
 * Name/lore are not plain fields here - they come from variableBindings
 * (even a literal, unresolved string is a STATIC-policy binding), per
 * IMPLEMENTATION_PLAN.md's "name and lore as variable-bound strings".
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
        List<KnkConditionBinding> conditions
) {}

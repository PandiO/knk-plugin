package net.knightsandkings.knk.core.domain.menu;

import java.util.List;

/**
 * A logical grouping of menu items within a MenuTemplate (FR-2.1.2).
 */
public record KnkMenuSectionTemplate(
        Integer id,
        String name,
        String kind,
        Integer sortOrder,
        Integer displaySlot,
        Integer width,
        Integer height,
        String positionMode,
        String alignVertical,
        String alignHorizontal,
        String overflow,
        String listMode,
        String priority,
        String visibilityPermission,
        Boolean searchable,
        List<KnkMenuItemTemplate> items,
        List<KnkVariableBinding> variableBindings,
        String contentSourceId,
        String contentSourceParamsJson
) {}

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
        String contentSourceParamsJson,
        // Menu follow-up 2026-09-26: rows a Dynamic menu always keeps for this section.
        Integer minHeight
) {

    /** Shape before minHeight. */
    public KnkMenuSectionTemplate(Integer id, String name, String kind, Integer sortOrder, Integer displaySlot, Integer width,
                                  Integer height, String positionMode, String alignVertical, String alignHorizontal,
                                  String overflow, String listMode, String priority, String visibilityPermission,
                                  Boolean searchable, List<KnkMenuItemTemplate> items, List<KnkVariableBinding> variableBindings,
                                  String contentSourceId, String contentSourceParamsJson) {
        this(id, name, kind, sortOrder, displaySlot, width, height, positionMode, alignVertical, alignHorizontal, overflow,
                listMode, priority, visibilityPermission, searchable, items, variableBindings, contentSourceId,
                contentSourceParamsJson, null);
    }
}

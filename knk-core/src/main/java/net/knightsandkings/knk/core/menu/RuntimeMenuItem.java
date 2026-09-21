package net.knightsandkings.knk.core.menu;

import net.knightsandkings.knk.core.domain.menu.KnkActionBinding;
import net.knightsandkings.knk.core.domain.menu.KnkConditionBinding;
import net.knightsandkings.knk.core.domain.menu.KnkVariableBinding;

import java.util.List;

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
}

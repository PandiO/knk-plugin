package net.knightsandkings.knk.core.domain.menu;

/**
 * A data-driven click-time condition: a registered ConditionRegistry key
 * (conditionTypeId) plus a params map serialized as JSON. When this
 * appears in a MenuItemTemplate's own conditions list it gates the whole
 * item; when it appears in an ActionBinding's conditions list it gates
 * just that one action (DESIGN_REVIEW.md 2.2).
 * <p>
 * {@code phase} (InventoryMenu Phase 9, E5): "Click" (null = Click) or
 * "Render" - see {@code MenuConditionPhase}.
 */
public record KnkConditionBinding(
        Integer id,
        String conditionTypeId,
        String paramsJson,
        Integer sortOrder,
        String phase
) {

    /** Pre-Phase-9 shape (no {@code phase} - i.e. "Click"). */
    public KnkConditionBinding(Integer id, String conditionTypeId, String paramsJson, Integer sortOrder) {
        this(id, conditionTypeId, paramsJson, sortOrder, null);
    }
}

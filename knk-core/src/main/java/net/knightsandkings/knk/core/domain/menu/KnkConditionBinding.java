package net.knightsandkings.knk.core.domain.menu;

/**
 * A data-driven click-time condition: a registered ConditionRegistry key
 * (conditionTypeId) plus a params map serialized as JSON. When this
 * appears in a MenuItemTemplate's own conditions list it gates the whole
 * item; when it appears in an ActionBinding's conditions list it gates
 * just that one action (DESIGN_REVIEW.md 2.2).
 */
public record KnkConditionBinding(
        Integer id,
        String conditionTypeId,
        String paramsJson,
        Integer sortOrder
) {}

package net.knightsandkings.knk.core.domain.menu;

import java.util.List;

/**
 * A data-driven click action on a MenuItemTemplate: a registered
 * ActionRegistry key (actionTypeId) plus a params map serialized as JSON,
 * not inline code.
 */
public record KnkActionBinding(
        Integer id,
        String actionTypeId,
        String paramsJson,
        Integer sortOrder,
        List<KnkConditionBinding> conditions
) {}

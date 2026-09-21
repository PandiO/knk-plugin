package net.knightsandkings.knk.core.domain.menu;

/**
 * A getter-chain expression bound to one rendered property of a section or
 * item (e.g. Name, or one lore line), with the DESIGN_REVIEW.md cache
 * -invalidation policy attached (refreshPolicy: STATIC/ON_DIRTY/TTL).
 */
public record KnkVariableBinding(
        Integer id,
        String targetProperty,
        Integer sortOrder,
        String expression,
        String refreshPolicy,
        Integer ttlTicks
) {}

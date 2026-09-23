package net.knightsandkings.knk.core.domain.item;

/**
 * One entry in an ItemBlueprint's chronologically-ordered provenance collection
 * (docs/specs/items/IMPLEMENTATION_PLAN.md §3.2). sequenceNumber = 0 is the production/procurement
 * origin; higher numbers are later alteration-history entries (only 0 is populated in Phase 1).
 */
public record KnkItemBlueprintOrigin(
        Integer domainId,
        String domainName,
        String domainType,
        Integer sequenceNumber
) {}

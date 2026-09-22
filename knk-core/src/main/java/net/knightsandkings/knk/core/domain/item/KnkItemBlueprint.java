package net.knightsandkings.knk.core.domain.item;

import java.util.List;

public record KnkItemBlueprint(
        Integer id,
        String name,
        String description,
        Integer iconMaterialRefId,
        String iconNamespaceKey,
        String defaultDisplayName,
        String defaultDisplayDescription,
        Integer defaultQuantity,
        Integer maxStackSize,
        List<KnkItemBlueprintDefaultEnchantment> defaultEnchantments,
        Integer defaultEnchantmentsCount,
        KnkGrade grade,
        // Direct ItemBlueprintTag entries only, not category-inherited tags (docs/specs/items/
        // IMPLEMENTATION_PLAN.md §7.9).
        List<KnkTag> tags,
        // Ordered by sequenceNumber; see KnkItemBlueprintOrigin's own doc comment.
        List<KnkItemBlueprintOrigin> origins
) {}

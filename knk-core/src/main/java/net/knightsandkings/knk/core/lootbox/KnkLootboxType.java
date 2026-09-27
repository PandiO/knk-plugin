package net.knightsandkings.knk.core.lootbox;

/**
 * An enabled lootbox type as the runtime config lists it (docs/specs/lootboxes/DESIGN.md §3.3): one per item
 * category. Mirrors knk-web-api's {@code LootboxRuntimeTypeDto}.
 *
 * @param displayMaterialKey the model a box shows: the type's material, else the category icon, else a chest
 * @param announceMinItemStars null = the global threshold
 */
public record KnkLootboxType(
        int id,
        String name,
        int categoryId,
        String categoryName,
        String displayMaterialKey,
        int spawnWeight,
        int minBoxStars,
        int maxBoxStars,
        Integer maxClaimsPerPlayerPerDay,
        Integer announceMinItemStars
) {
}

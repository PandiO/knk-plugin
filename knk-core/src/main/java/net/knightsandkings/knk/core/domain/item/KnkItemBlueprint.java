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
        List<KnkItemBlueprintOrigin> origins,
        // PandiO/knk-workspace#232: the shield's banner (null = plain / not a shield) and the armour trim (null =
        // none), applied by ItemAppearanceBukkitMapper when the item is built.
        KnkShieldBanner shieldBanner,
        KnkArmorTrim armorTrim
) {
    /** The blueprint without a look (#232): what every caller built before shields and trims were stored. */
    public KnkItemBlueprint(
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
            List<KnkTag> tags,
            List<KnkItemBlueprintOrigin> origins
    ) {
        this(id, name, description, iconMaterialRefId, iconNamespaceKey, defaultDisplayName, defaultDisplayDescription,
                defaultQuantity, maxStackSize, defaultEnchantments, defaultEnchantmentsCount, grade, tags, origins, null, null);
    }

    /** The same blueprint with another grade (lootbox claims carry the box's item grade). */
    public KnkItemBlueprint withGrade(KnkGrade newGrade) {
        return new KnkItemBlueprint(id, name, description, iconMaterialRefId, iconNamespaceKey, defaultDisplayName,
                defaultDisplayDescription, defaultQuantity, maxStackSize, defaultEnchantments, defaultEnchantmentsCount,
                newGrade, tags, origins, shieldBanner, armorTrim);
    }

    /** The same blueprint with another look (#232: a lootbox claim carries the instance's frozen one). */
    public KnkItemBlueprint withLook(KnkShieldBanner newShieldBanner, KnkArmorTrim newArmorTrim) {
        return new KnkItemBlueprint(id, name, description, iconMaterialRefId, iconNamespaceKey, defaultDisplayName,
                defaultDisplayDescription, defaultQuantity, maxStackSize, defaultEnchantments, defaultEnchantmentsCount,
                grade, tags, origins, newShieldBanner, newArmorTrim);
    }
}

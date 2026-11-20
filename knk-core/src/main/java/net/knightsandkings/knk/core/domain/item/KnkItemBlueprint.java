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
        KnkArmorTrim armorTrim,
        // Siege shop L2/L3 (PandiO/knk-workspace#867, #868): a vanilla potion key such as minecraft:strong_healing for a
        // potion, splash potion, lingering potion or tipped arrow (null = none), applied when the item is built.
        String potionType
) {
    /** The blueprint without a potion type (#868): what every caller built before potions were stored. */
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
            List<KnkItemBlueprintOrigin> origins,
            KnkShieldBanner shieldBanner,
            KnkArmorTrim armorTrim
    ) {
        this(id, name, description, iconMaterialRefId, iconNamespaceKey, defaultDisplayName, defaultDisplayDescription,
                defaultQuantity, maxStackSize, defaultEnchantments, defaultEnchantmentsCount, grade, tags, origins,
                shieldBanner, armorTrim, null);
    }

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
                defaultQuantity, maxStackSize, defaultEnchantments, defaultEnchantmentsCount, grade, tags, origins, null, null, null);
    }

    /** The same blueprint with another grade (lootbox claims carry the box's item grade). */
    public KnkItemBlueprint withGrade(KnkGrade newGrade) {
        return new KnkItemBlueprint(id, name, description, iconMaterialRefId, iconNamespaceKey, defaultDisplayName,
                defaultDisplayDescription, defaultQuantity, maxStackSize, defaultEnchantments, defaultEnchantmentsCount,
                newGrade, tags, origins, shieldBanner, armorTrim, potionType);
    }

    /** The same blueprint with another look (#232: a lootbox claim carries the instance's frozen one). */
    public KnkItemBlueprint withLook(KnkShieldBanner newShieldBanner, KnkArmorTrim newArmorTrim) {
        return new KnkItemBlueprint(id, name, description, iconMaterialRefId, iconNamespaceKey, defaultDisplayName,
                defaultDisplayDescription, defaultQuantity, maxStackSize, defaultEnchantments, defaultEnchantmentsCount,
                grade, tags, origins, newShieldBanner, newArmorTrim, potionType);
    }
}

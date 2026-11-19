package net.knightsandkings.knk.core.items.identity;

/**
 * What the resolver needs to know about one item stack, read by the platform adapter (knk-paper
 * {@code ItemIdentityFactsReader}).
 *
 * @param materialKey              the item's material, e.g. {@code minecraft:oak_log}
 * @param instanceId               {@code knightsandkings:knk_item_instance}, or null
 * @param blueprintTag             {@code knightsandkings:knk_blueprint}, or null
 * @param customName               the item has a custom (display) name
 * @param customLore               the item has lore
 * @param otherData                the item carries persistent data other than the two tags above (another plugin's,
 *                                 or a K&amp;K tag of an item made without a blueprint tag)
 * @param plainWithoutBlueprintTag without its blueprint tag the item is exactly the plain vanilla item of its material
 *                                 (no name, lore, enchantments, damage or other component)
 */
public record ItemIdentityFacts(
        String materialKey,
        Long instanceId,
        Integer blueprintTag,
        boolean customName,
        boolean customLore,
        boolean otherData,
        boolean plainWithoutBlueprintTag
) {
    /** A plain vanilla item of {@code materialKey} without any tag. */
    public static ItemIdentityFacts plain(String materialKey) {
        return new ItemIdentityFacts(materialKey, null, null, false, false, false, true);
    }
}

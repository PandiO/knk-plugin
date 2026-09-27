package net.knightsandkings.knk.core.lootbox;

/** One enchantment on a claimed item: a blueprint default or a rolled one (the item's final set). */
public record KnkLootboxClaimEnchantment(int definitionId, String key, boolean isCustom, int level) {
}

package net.knightsandkings.knk.core.survival.mysterybox;

import java.util.List;

/**
 * The result of one pick (§8a): the entry and, for an item entry, the enchantments that hit. The reel's passing
 * items are further picks from the same pool with the real odds.
 */
public record MysteryBoxRoll(MysteryBoxEntry entry, List<EnchantmentChance> rolledEnchantments) {

    public MysteryBoxRoll {
        if (entry == null) throw new IllegalArgumentException("entry is required");
        rolledEnchantments = rolledEnchantments == null ? List.of() : List.copyOf(rolledEnchantments);
    }
}

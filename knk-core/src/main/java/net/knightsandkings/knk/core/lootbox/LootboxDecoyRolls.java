package net.knightsandkings.knk.core.lootbox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;

/**
 * The enchantments a passing item of the opening reel carries (docs/specs/lootboxes/DESIGN.md §3.9). The real drop's
 * enchantments are rolled by the API; the winner shows them, so a passing item without any would give the result away.
 * This rolls the same enchant rolls the box has (from its odds preview): each hits on its chance, with a uniform
 * level of the range its item grade allows. Applicability and conflicts are left to the item assembler's vanilla
 * rules, as for the real drop. Bukkit-free.
 */
public final class LootboxDecoyRolls {

    private LootboxDecoyRolls() {
    }

    /**
     * @param rolls      the box's enchant rolls ({@link KnkLootboxOdds#enchantments()})
     * @param itemStars  the item grade of the passing item; non-positive = unknown, nothing is rolled
     * @param random     0 (inclusive) - 1 (exclusive)
     * @return the rolled enchantments in roll order (possibly empty)
     */
    public static List<KnkLootboxClaimEnchantment> roll(List<KnkLootboxOdds.Enchantment> rolls, int itemStars, DoubleSupplier random) {
        List<KnkLootboxClaimEnchantment> result = new ArrayList<>();
        if (rolls == null || itemStars <= 0) {
            return result;
        }
        for (KnkLootboxOdds.Enchantment roll : rolls) {
            if (roll == null) {
                continue;
            }
            KnkLootboxOdds.LevelRange range = rangeFor(roll, itemStars);
            if (range == null) {
                continue; // dropped on this grade by the level cap
            }
            if (random.getAsDouble() * 100.0 >= roll.hitPercent()) {
                continue;
            }
            int span = range.maxLevel() - range.minLevel() + 1;
            int level = range.minLevel() + Math.min(span - 1, (int) (random.getAsDouble() * span));
            result.add(new KnkLootboxClaimEnchantment(roll.definitionId(), roll.key(), roll.isCustom(), level));
        }
        return result;
    }

    private static KnkLootboxOdds.LevelRange rangeFor(KnkLootboxOdds.Enchantment roll, int itemStars) {
        for (KnkLootboxOdds.LevelRange range : roll.levelsByGrade()) {
            if (range.stars() == itemStars) {
                return range.minLevel() != null && range.maxLevel() != null && range.minLevel() >= 1
                        && range.maxLevel() >= range.minLevel() ? range : null;
            }
        }
        return null;
    }
}

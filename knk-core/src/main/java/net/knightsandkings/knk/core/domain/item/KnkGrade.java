package net.knightsandkings.knk.core.domain.item;

/**
 * An item grade ("Common" ... "Divine", 1-10 stars).
 *
 * @param dropChance             percent chance (0-100) an item of this grade drops; null when not set.
 *                               Carried for display/future loot use, nothing in the plugin rolls it yet
 * @param enchantLevelCapDivisor v1's enchant-book level cap divisor (Linear KNG-6,
 *                               docs/specs/items/GRADE_DROPCHANCE.md): grades 1-5 use 5, 4, 3, 2, 1;
 *                               null means uncapped (grades 6-10). See {@link #capEnchantLevel}
 */
public record KnkGrade(
        Integer id,
        String name,
        Integer stars,
        Double dropChance,
        Integer enchantLevelCapDivisor
) {
    public KnkGrade(Integer id, String name, Integer stars) {
        this(id, name, stars, null, null);
    }

    /**
     * The highest level an enchantment may reach on an item of this grade, or null when uncapped.
     * <p>
     * Reproduces v1's {@code EnchantbookClick.canEnchant()}:
     * {@code Math.round(enchantmentMaxLevel / (6 - grade))}, where both operands are ints, so the division
     * truncates before {@code Math.round} ever sees it. Sharpness (max 5) on a 1-star item: 5 / 5 = 1;
     * on a 2-star item: 5 / 4 = 1; Unbreaking (max 3) on a 1-star item: 3 / 5 = 0, so it can't go on at all.
     * A null or non-positive divisor is uncapped (the API rejects values below 1).
     *
     * @param enchantmentMaxLevel the enchantment definition's max level (v1's {@code Enchantments.MaxLevel})
     */
    public Integer capEnchantLevel(int enchantmentMaxLevel) {
        if (enchantLevelCapDivisor == null || enchantLevelCapDivisor < 1) {
            return null;
        }
        return Math.max(0, enchantmentMaxLevel) / enchantLevelCapDivisor;
    }

    public boolean capsEnchantLevels() {
        return enchantLevelCapDivisor != null && enchantLevelCapDivisor >= 1;
    }
}

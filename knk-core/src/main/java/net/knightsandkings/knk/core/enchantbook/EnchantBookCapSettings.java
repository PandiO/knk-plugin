package net.knightsandkings.knk.core.enchantbook;

import net.knightsandkings.knk.core.domain.item.GradeCatalog;

/**
 * The {@code enchant-books.grade-cap} block of config.yml (Linear KNG-6, docs/specs/items/GRADE_DROPCHANCE.md
 * §5-§6).
 *
 * @param enabled          false turns the grade cap off entirely (books behave as before KNG-6)
 * @param applyToCustom    v1 never capped custom enchantments (its {@code canEnchant} returned 2 for them), so
 *                         the default is false
 * @param ungradedStars    the grade assumed for items without one (vanilla-crafted, not from a graded
 *                         blueprint); 1 = most capped, so vanilla gear is no way around the cap. 0 = uncapped
 * @param bonusLevelChance chance (0-1) of v1's bonus level ({@link EnchantBookRules#withBonus}). v1 rolled
 *                         {@code nextInt(100) <= 20}, i.e. 21%; the default is the 20% it was meant to be
 */
public record EnchantBookCapSettings(boolean enabled, boolean applyToCustom, int ungradedStars, double bonusLevelChance) {

    public static final EnchantBookCapSettings DEFAULTS = new EnchantBookCapSettings(true, false, 1, 0.20);

    public EnchantBookCapSettings {
        ungradedStars = Math.max(0, ungradedStars);
        bonusLevelChance = Double.isNaN(bonusLevelChance) ? 0 : Math.max(0, Math.min(1, bonusLevelChance));
    }

    /**
     * The level cap for the book's enchantment on an item, or null when uncapped.
     *
     * @param itemStars           the item's grade stars, null when it has none
     * @param enchantmentMaxLevel the enchantment definition's max level
     */
    public Integer levelCap(GradeCatalog catalog, Integer itemStars, EnchantBookPayload.Kind kind, int enchantmentMaxLevel) {
        if (!enabled) return null;
        if (kind == EnchantBookPayload.Kind.CUSTOM && !applyToCustom) return null;
        int stars = itemStars != null && itemStars > 0 ? itemStars : ungradedStars;
        if (stars <= 0) return null;
        return catalog.byStars(stars).map(grade -> EnchantBookRules.levelCap(grade, enchantmentMaxLevel)).orElse(null);
    }

    /** @param roll uniform in [0, 1) */
    public boolean bonus(double roll) {
        return roll < bonusLevelChance;
    }
}

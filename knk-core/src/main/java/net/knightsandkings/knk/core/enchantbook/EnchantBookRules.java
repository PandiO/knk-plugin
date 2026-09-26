package net.knightsandkings.knk.core.enchantbook;

import net.knightsandkings.knk.core.domain.item.KnkGrade;

/**
 * Whether a permanent enchantment book may go on an item
 * (docs/specs/enchantment-books/ENCHANTMENT_BOOK_APPLICATION.md §3.2; Linear KNG-5). The same checks as the
 * siege books on {@code claude/siege-minigame} ({@code SiegeEnchantBooks.evaluate}) minus the match gate:
 * compatible, not conflicting, and an improvement - plus v1's grade level cap (Linear KNG-6,
 * docs/specs/items/GRADE_DROPCHANCE.md). The Paper side reads the facts off the item ({@link Target}); this
 * class only decides, so it is testable without a server.
 */
public final class EnchantBookRules {
    private EnchantBookRules() {}

    public enum ApplyResult { APPLIED, INVALID_BOOK, NOT_ENCHANTABLE, CONFLICT, LEVEL_CAPPED, NO_IMPROVEMENT }

    /**
     * Facts about the target item, read by the caller.
     *
     * @param usable        not air, not a (enchanted) book
     * @param compatible    vanilla: {@code Enchantment#canEnchantItem}; custom: {@link #customCompatible}
     * @param conflicts     vanilla: conflicts with an enchantment the item already has (custom: always false)
     * @param existingLevel the item's current level of the book's enchantment, 0 when it has none
     * @param levelCap      the highest level the item's grade allows for this enchantment ({@link #levelCap}),
     *                      null when uncapped
     */
    public record Target(boolean usable, boolean compatible, boolean conflicts, int existingLevel, Integer levelCap) {
        /** Uncapped. */
        public Target(boolean usable, boolean compatible, boolean conflicts, int existingLevel) {
            this(usable, compatible, conflicts, existingLevel, null);
        }
    }

    /** {@code book} null means the item isn't a (valid) permanent book, or its enchantment isn't known here. */
    public static ApplyResult evaluate(EnchantBookPayload book, Target target) {
        if (book == null) return ApplyResult.INVALID_BOOK;
        if (target == null || !target.usable() || !target.compatible()) return ApplyResult.NOT_ENCHANTABLE;
        if (target.conflicts()) return ApplyResult.CONFLICT;
        // v1: "This item reached the max. enchantmentlevel for this enchantment" - no headroom left under the cap.
        if (target.levelCap() != null && target.existingLevel() >= target.levelCap()) return ApplyResult.LEVEL_CAPPED;
        if (appliedLevel(target, book.level()) <= target.existingLevel()) return ApplyResult.NO_IMPROVEMENT;
        return ApplyResult.APPLIED;
    }

    /** The level a book gives: {@code max(existing, book)} (as siege books; vanilla anvil behaviour). */
    public static int appliedLevel(int existingLevel, int bookLevel) {
        return Math.max(existingLevel, bookLevel);
    }

    /**
     * The level a book gives under the grade cap: {@code max(existing, book)}, lowered to the cap. v1 never
     * raised an item above its cap but still let a book add whatever headroom was left, so a Sharpness III book
     * on a 1-star sword (cap 1) gives Sharpness I rather than being refused.
     */
    public static int appliedLevel(Target target, int bookLevel) {
        int level = appliedLevel(target.existingLevel(), bookLevel);
        return target.levelCap() != null ? Math.min(level, target.levelCap()) : level;
    }

    /** True when the cap lowered what the book would otherwise give. */
    public static boolean cappedBelowBook(Target target, int bookLevel) {
        return appliedLevel(target, bookLevel) < appliedLevel(target.existingLevel(), bookLevel);
    }

    /**
     * The grade cap for an enchantment on an item of {@code grade} ({@link KnkGrade#capEnchantLevel}), or null
     * when uncapped: no grade, or a grade without a divisor (6-10 stars).
     *
     * @param enchantmentMaxLevel the enchantment definition's max level (v1 divided its own
     *                            {@code Enchantments.MaxLevel}, not vanilla's)
     */
    public static Integer levelCap(KnkGrade grade, int enchantmentMaxLevel) {
        return grade == null ? null : grade.capEnchantLevel(enchantmentMaxLevel);
    }

    /**
     * v1's bonus level: on a successful roll a book gives one level more, but never above the cap (v1 only
     * rolled when there were at least two levels of headroom), nor - when uncapped - above the enchantment's
     * max level (v1's highest cap, grade 5, was exactly the max level).
     *
     * @param level the level {@link #appliedLevel(Target, int)} gave
     */
    public static int withBonus(int level, Integer levelCap, int enchantmentMaxLevel) {
        int limit = levelCap != null ? levelCap : enchantmentMaxLevel;
        return level + 1 <= limit ? level + 1 : level;
    }

    /**
     * Custom enchantments go on gear only - anything with durability (weapons, tools, armor, bows, shields):
     * their effects fire from combat/interaction with held or worn items.
     */
    public static boolean customCompatible(int maxDurability) {
        return maxDurability > 0;
    }
}

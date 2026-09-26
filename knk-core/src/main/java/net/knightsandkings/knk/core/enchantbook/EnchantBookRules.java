package net.knightsandkings.knk.core.enchantbook;

/**
 * Whether a permanent enchantment book may go on an item
 * (docs/specs/enchantment-books/ENCHANTMENT_BOOK_APPLICATION.md §3.2; Linear KNG-5). The same checks as the
 * siege books on {@code claude/siege-minigame} ({@code SiegeEnchantBooks.evaluate}) minus the match gate:
 * compatible, not conflicting, and an improvement. The Paper side reads the facts off the item
 * ({@link Target}); this class only decides, so it is testable without a server.
 * <p>
 * The v1 grade/level cap (Linear KNG-6) is deliberately not here yet; when it lands it is one more check
 * before {@link ApplyResult#NO_IMPROVEMENT}.
 */
public final class EnchantBookRules {
    private EnchantBookRules() {}

    public enum ApplyResult { APPLIED, INVALID_BOOK, NOT_ENCHANTABLE, CONFLICT, NO_IMPROVEMENT }

    /**
     * Facts about the target item, read by the caller.
     *
     * @param usable        not air, not a (enchanted) book
     * @param compatible    vanilla: {@code Enchantment#canEnchantItem}; custom: {@link #customCompatible}
     * @param conflicts     vanilla: conflicts with an enchantment the item already has (custom: always false)
     * @param existingLevel the item's current level of the book's enchantment, 0 when it has none
     */
    public record Target(boolean usable, boolean compatible, boolean conflicts, int existingLevel) {}

    /** {@code book} null means the item isn't a (valid) permanent book, or its enchantment isn't known here. */
    public static ApplyResult evaluate(EnchantBookPayload book, Target target) {
        if (book == null) return ApplyResult.INVALID_BOOK;
        if (target == null || !target.usable() || !target.compatible()) return ApplyResult.NOT_ENCHANTABLE;
        if (target.conflicts()) return ApplyResult.CONFLICT;
        if (appliedLevel(target.existingLevel(), book.level()) <= target.existingLevel()) return ApplyResult.NO_IMPROVEMENT;
        return ApplyResult.APPLIED;
    }

    /** The level a book gives: {@code max(existing, book)} (as siege books; vanilla anvil behaviour). */
    public static int appliedLevel(int existingLevel, int bookLevel) {
        return Math.max(existingLevel, bookLevel);
    }

    /**
     * Custom enchantments go on gear only - anything with durability (weapons, tools, armor, bows, shields):
     * their effects fire from combat/interaction with held or worn items.
     */
    public static boolean customCompatible(int maxDurability) {
        return maxDurability > 0;
    }
}

package net.knightsandkings.knk.core.survival.mysterybox;

/** The two Mystery Box variants (S24, §8a): same point type and roll path, different pool kind. */
public enum MysteryBoxVariant {
    /** Item blueprints with optional per-entry enchantment chances. */
    ITEMS,
    /** Enchanted books: an enchantment and level per entry. */
    ENCHANTED_BOOKS
}

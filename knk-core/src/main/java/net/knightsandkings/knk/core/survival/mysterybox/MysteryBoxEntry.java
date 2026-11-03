package net.knightsandkings.knk.core.survival.mysterybox;

import java.util.List;

/** One entry of a Mystery Box pool with its percentage drop chance in basis points. */
public sealed interface MysteryBoxEntry {

    /** The drop chance in basis points (1 % = 100). */
    int basisPoints();

    /** A human-readable reference for messages. */
    String describe();

    /** An item blueprint with optional per-entry enchantment chances. */
    record ItemEntry(String blueprintId, int basisPoints, List<EnchantmentChance> enchantments) implements MysteryBoxEntry {
        public ItemEntry {
            if (blueprintId == null || blueprintId.isBlank()) throw new IllegalArgumentException("blueprintId is required");
            enchantments = enchantments == null ? List.of() : List.copyOf(enchantments);
        }

        public static ItemEntry of(String blueprintId, double percent) {
            return new ItemEntry(blueprintId, BasisPoints.fromPercent(percent), List.of());
        }

        @Override
        public String describe() {
            return "item " + blueprintId;
        }
    }

    /** An enchanted book with one enchantment and level. */
    record BookEntry(String enchantmentId, int level, int basisPoints) implements MysteryBoxEntry {
        public BookEntry {
            if (enchantmentId == null || enchantmentId.isBlank()) throw new IllegalArgumentException("enchantmentId is required");
            if (level < 1) throw new IllegalArgumentException("level must be >= 1");
        }

        public static BookEntry of(String enchantmentId, int level, double percent) {
            return new BookEntry(enchantmentId, level, BasisPoints.fromPercent(percent));
        }

        @Override
        public String describe() {
            return "book " + enchantmentId + " " + level;
        }
    }
}

package net.knightsandkings.knk.core.enchantbook;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * What a permanent enchantment book teaches (docs/specs/enchantment-books/ENCHANTMENT_BOOK_APPLICATION.md
 * §3.1; Linear KNG-5): a vanilla enchantment by namespace key ({@code minecraft:sharpness}) or a custom Knk
 * enchantment by {@code EnchantmentRegistry} id ({@code poison}), at a level.
 * <p>
 * Stored as one string in the book's {@code PersistentDataContainer}: {@code kind;key;level}, e.g.
 * {@code vanilla;minecraft:sharpness;3} or {@code custom;poison;2}. That tag is the book's only source of
 * truth - its lore is display output.
 */
public record EnchantBookPayload(Kind kind, String enchantmentKey, int level) {

    public enum Kind { VANILLA, CUSTOM }

    public EnchantBookPayload {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(enchantmentKey, "enchantmentKey");
        enchantmentKey = enchantmentKey.trim().toLowerCase(Locale.ROOT);
        if (enchantmentKey.isEmpty() || enchantmentKey.contains(";")) {
            throw new IllegalArgumentException("Invalid enchantment key: '" + enchantmentKey + "'");
        }
        if (level < 1) {
            throw new IllegalArgumentException("level must be >= 1, was " + level);
        }
    }

    public static EnchantBookPayload vanilla(String namespaceKey, int level) {
        return new EnchantBookPayload(Kind.VANILLA, namespaceKey, level);
    }

    public static EnchantBookPayload custom(String enchantmentId, int level) {
        return new EnchantBookPayload(Kind.CUSTOM, enchantmentId, level);
    }

    public String encode() {
        return kind.name().toLowerCase(Locale.ROOT) + ';' + enchantmentKey + ';' + level;
    }

    /** Lenient: anything malformed decodes to empty, never throws. */
    public static Optional<EnchantBookPayload> decode(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String[] parts = raw.split(";", -1);
        if (parts.length != 3) return Optional.empty();
        try {
            Kind kind = Kind.valueOf(parts[0].trim().toUpperCase(Locale.ROOT));
            int level = Integer.parseInt(parts[2].trim());
            return Optional.of(new EnchantBookPayload(kind, parts[1], level));
        } catch (IllegalArgumentException e) { // bad kind, bad number, bad key or level
            return Optional.empty();
        }
    }
}

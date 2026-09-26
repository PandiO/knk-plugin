package net.knightsandkings.knk.core.enchantbook;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/** Display text for permanent enchantment books (docs/specs/enchantment-books/ENCHANTMENT_BOOK_APPLICATION.md §3.1). */
public final class EnchantBookText {
    private EnchantBookText() {}

    private static final String[] ROMAN = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

    /**
     * The book's "teaches" lore text, e.g. {@code Teaches: Poison II}. The prefix matters: the custom
     * enchantment lore parser treats any line shaped {@code <Custom Name> <Roman>} as a live enchantment on
     * the item, so a bare {@code Poison II} would make the book itself poison people.
     */
    public static String teaches(String displayName, int level) {
        return "Teaches: " + displayName + " " + roman(level);
    }

    /** I..X, then plain digits (vanilla levels above the vanilla cap exist in v1 data). */
    public static String roman(int level) {
        return level >= 1 && level <= ROMAN.length ? ROMAN[level - 1] : Integer.toString(level);
    }

    /** {@code minecraft:fire_aspect} / {@code flash_chaos} → {@code Fire Aspect} / {@code Flash Chaos}. */
    public static String displayNameFromKey(String key) {
        if (key == null || key.isBlank()) return "Unknown";
        String bare = key.substring(key.indexOf(':') + 1);
        return Arrays.stream(bare.split("[_\\s]+"))
                .filter(w -> !w.isEmpty())
                .map(w -> w.substring(0, 1).toUpperCase(Locale.ROOT) + w.substring(1).toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(" "));
    }
}

package net.knightsandkings.knk.core.enchantbook;

import net.knightsandkings.knk.core.enchantbook.EnchantBookRules.ApplyResult;

import java.util.Arrays;
import java.util.List;
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

    /** {@code Common ★★} for grade explanations; {@code ?} when the grade is unknown. */
    public static String gradeLabel(String name, Integer stars) {
        String starText = stars != null && stars > 0 ? "★".repeat(stars) : "";
        if (name == null || name.isBlank()) return starText.isEmpty() ? "?" : starText;
        return starText.isEmpty() ? name : name + " " + starText;
    }

    /**
     * Why a book can't go on an item, as lore lines for the chooser (the developer's request after the
     * 2026-09-26 manual test: every item that could hold the enchantment is shown, each saying whether and why
     * not). Empty for {@link ApplyResult#APPLIED}.
     *
     * @param enchantment   "Sharpness" (no level)
     * @param levelCap      the grade cap for this enchantment on the item, null when uncapped
     * @param gradeLabel    {@link #gradeLabel} of the grade the cap used
     * @param ungraded      the item has no grade of its own (the cap used the configured one)
     * @param conflictsWith display name of the enchantment it conflicts with, null when unknown
     */
    public static List<String> cannotApply(ApplyResult result, String enchantment, int bookLevel, int existingLevel,
                                           Integer levelCap, String gradeLabel, boolean ungraded, String conflictsWith) {
        return switch (result) {
            case APPLIED -> List.of();
            case LEVEL_CAPPED -> {
                String grade = ungraded
                        ? "It has no grade, so it counts as " + gradeLabel + "."
                        : "Its grade is " + gradeLabel + ".";
                int cap = levelCap != null ? levelCap : 0;
                String limit;
                if (cap <= 0) {
                    limit = enchantment + " can't go on items of this grade.";
                } else if (existingLevel == cap) {
                    limit = "It allows " + enchantment + " up to " + roman(cap) + ", which it already has.";
                } else {
                    limit = "It allows " + enchantment + " up to " + roman(cap) + "; it already has " + roman(existingLevel) + ".";
                }
                yield List.of(grade, limit);
            }
            case CONFLICT -> List.of("Conflicts with " + (conflictsWith != null ? conflictsWith : "an enchantment it already has") + ".");
            case NO_IMPROVEMENT -> List.of(existingLevel > bookLevel
                    ? "Already has " + enchantment + " " + roman(existingLevel) + "; this book is " + roman(bookLevel) + "."
                    : "Already has " + enchantment + " " + roman(existingLevel) + ".");
            case NOT_ENCHANTABLE -> List.of("That enchantment can't go on this item.");
            case INVALID_BOOK -> List.of("This enchantment book doesn't work on this server.");
        };
    }
}

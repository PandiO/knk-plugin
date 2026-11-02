package net.knightsandkings.knk.core.domain.item;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Composes the player-facing sections of item lore with stable spacing (KNG-29).
 * Custom enchantments/abilities are first, description text follows, and the grade
 * block always has one blank line directly above it. Existing blank lines are
 * discarded before rebuilding, making the operation safe to repeat.
 */
public final class ItemLoreLayout {

    private ItemLoreLayout() {
    }

    public static List<String> compose(List<String> enchantmentLines, List<String> remainingLore) {
        List<String> enchantments = nonBlank(enchantmentLines);
        List<String> remaining = nonBlank(remainingLore);

        int gradeIndex = -1;
        for (int i = 0; i < remaining.size(); i++) {
            if (isGradeLine(remaining.get(i))) {
                gradeIndex = i;
                break;
            }
        }

        List<String> description = gradeIndex < 0 ? remaining : remaining.subList(0, gradeIndex);
        List<String> gradeAndOrigin = gradeIndex < 0 ? List.of() : remaining.subList(gradeIndex, remaining.size());

        List<String> result = new ArrayList<>();
        result.addAll(enchantments);
        if (!enchantments.isEmpty() && !description.isEmpty()) {
            result.add("");
        }
        result.addAll(description);

        if (!gradeAndOrigin.isEmpty()) {
            // The grade line is deliberately never the first/adjacent content line. Vanilla
            // enchantments render above lore, so even a grade-only lore starts with this spacer.
            result.add("");
            result.addAll(gradeAndOrigin);
        }

        return List.copyOf(result);
    }

    /**
     * The description part of an item's lore, without what the plugin generates when it builds a blueprint item
     * (PandiO/knk-workspace#40): blank spacer lines, the lines {@code isEnchantmentLine} recognises (custom
     * enchantments), and everything from the first {@code Grade:} or {@code Origin:} line on (that block always sits
     * at the bottom, see {@link #compose}). What remains is what an ItemBlueprint scan should offer as the Display
     * description; continuation lines of a wrapped line are still separate here (see
     * {@code net.knightsandkings.knk.core.util.LoreWrap#unwrap}).
     */
    public static List<String> descriptionLines(List<String> lore, Predicate<String> isEnchantmentLine) {
        if (lore == null || lore.isEmpty()) {
            return List.of();
        }
        List<String> description = new ArrayList<>();
        for (String line : lore) {
            if (line == null || line.isBlank()) {
                continue;
            }
            if (isGradeLine(line) || isOriginLine(line)) {
                break;
            }
            if (isEnchantmentLine != null && isEnchantmentLine.test(line)) {
                continue;
            }
            description.add(line);
        }
        return List.copyOf(description);
    }

    private static List<String> nonBlank(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        return lines.stream().filter(line -> line != null && !line.isBlank()).toList();
    }

    private static boolean isGradeLine(String line) {
        return plain(line).startsWith("Grade:");
    }

    /** {@code ItemBlueprintBukkitMapper.buildOriginLoreLine}: "&7Origin: name (type)". */
    private static boolean isOriginLine(String line) {
        return plain(line).startsWith("Origin:");
    }

    private static String plain(String line) {
        // Supports the legacy section-sign output and ampersand-authored lore.
        return line.replaceAll("(?i)[§&][0-9A-FK-ORX]", "").trim();
    }
}

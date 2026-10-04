package net.knightsandkings.knk.core.domain.item;

import java.util.ArrayList;
import java.util.List;

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

    private static List<String> nonBlank(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        return lines.stream().filter(line -> line != null && !line.isBlank()).toList();
    }

    private static boolean isGradeLine(String line) {
        // Supports the legacy section-sign output and ampersand-authored lore.
        String plain = line.replaceAll("(?i)[§&][0-9A-FK-ORX]", "").trim();
        return plain.startsWith("Grade:");
    }
}

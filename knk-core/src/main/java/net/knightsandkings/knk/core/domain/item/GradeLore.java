package net.knightsandkings.knk.core.domain.item;

import java.util.List;
import java.util.Optional;

/**
 * Reads the grade back from the v1-style star lore line ({@code "§l§bGrade: ★★★"}) that
 * {@code ItemBlueprintBukkitMapper} has written onto blueprint items since before they got a machine-readable
 * grade tag (Linear KNG-6, docs/specs/items/GRADE_DROPCHANCE.md §4). Only a fallback for items created before
 * the tag existed; the tag wins whenever it is present.
 */
public final class GradeLore {

    static final String LABEL = "Grade:";
    static final char STAR = '★';

    private GradeLore() {
    }

    /** The number of stars on the first {@code Grade:} line, or empty when there is none (or it has no stars). */
    public static Optional<Integer> starsFromLore(List<String> lore) {
        if (lore == null) {
            return Optional.empty();
        }
        for (String line : lore) {
            if (line == null) continue;
            String plain = stripLegacyColors(line);
            int label = plain.indexOf(LABEL);
            if (label < 0) continue;
            int stars = 0;
            for (int i = label + LABEL.length(); i < plain.length(); i++) {
                if (plain.charAt(i) == STAR) stars++;
            }
            return stars > 0 ? Optional.of(stars) : Optional.empty();
        }
        return Optional.empty();
    }

    /** Drops {@code §x} / {@code &x} colour and format codes. */
    static String stripLegacyColors(String line) {
        StringBuilder out = new StringBuilder(line.length());
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if ((c == '§' || c == '&') && i + 1 < line.length()) {
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }
}

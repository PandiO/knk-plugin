package net.knightsandkings.knk.core.domain.item;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ItemLoreLayoutTest {

    private static final String ENCHANTMENT = "§7Poison II";
    private static final String DESCRIPTION = "§7A fine blade";
    private static final String GRADE = "§l§bGrade: ★★★";
    private static final String ORIGIN = "§7Origin: Cinix (Town)";

    @Test
    void descriptionAndGradeHaveASpacer() {
        assertEquals(List.of(DESCRIPTION, "", GRADE, ORIGIN),
                ItemLoreLayout.compose(List.of(), List.of(DESCRIPTION, GRADE, ORIGIN)));
    }

    @Test
    void enchantmentAndGradeHaveASpacer() {
        assertEquals(List.of(ENCHANTMENT, "", GRADE),
                ItemLoreLayout.compose(List.of(ENCHANTMENT), List.of(GRADE)));
    }

    @Test
    void enchantmentDescriptionAndGradeAreSeparateSections() {
        assertEquals(List.of(ENCHANTMENT, "", DESCRIPTION, "", GRADE, ORIGIN),
                ItemLoreLayout.compose(List.of(ENCHANTMENT), List.of(DESCRIPTION, GRADE, ORIGIN)));
    }

    @Test
    void gradeOnlyStartsWithASpacer() {
        assertEquals(List.of("", GRADE), ItemLoreLayout.compose(List.of(), List.of(GRADE)));
    }

    @Test
    void repeatedCompositionDoesNotStackBlankLines() {
        List<String> composed = ItemLoreLayout.compose(List.of(ENCHANTMENT),
                List.of("", DESCRIPTION, "", "", GRADE, ORIGIN));
        assertEquals(List.of(ENCHANTMENT, "", DESCRIPTION, "", GRADE, ORIGIN), composed);
        assertEquals(composed, ItemLoreLayout.compose(List.of(ENCHANTMENT), composed.subList(2, composed.size())));
    }
    // ===== PandiO/knk-workspace#40: the description part, without the generated lines =====

    @Test
    void descriptionLinesLeaveOutEnchantmentsSpacersGradeAndOrigin() {
        List<String> lore = ItemLoreLayout.compose(List.of(ENCHANTMENT, "§7Chaos I"),
                List.of("§8Forged in the last fire of a fallen dojo.", "§8Its edge never cools.", GRADE, ORIGIN));

        assertEquals(List.of("§8Forged in the last fire of a fallen dojo.", "§8Its edge never cools."),
                ItemLoreLayout.descriptionLines(lore, line -> line.equals(ENCHANTMENT) || line.equals("§7Chaos I")));
    }

    @Test
    void descriptionLinesStopAtAnOriginLineWithoutAGrade() {
        // No grade: compose keeps the origin line at the bottom of the description block.
        List<String> lore = ItemLoreLayout.compose(List.of(), List.of(DESCRIPTION, ORIGIN));

        assertEquals(List.of(DESCRIPTION), ItemLoreLayout.descriptionLines(lore, line -> false));
    }

    @Test
    void descriptionLinesOfGeneratedLinesOnlyAreEmpty() {
        assertEquals(List.of(), ItemLoreLayout.descriptionLines(List.of(ENCHANTMENT, "", "&l&bGrade: ★★"), ENCHANTMENT::equals));
        assertEquals(List.of(), ItemLoreLayout.descriptionLines(null, line -> false));
    }

    @Test
    void descriptionLinesKeepAPlainItemsLore() {
        assertEquals(List.of("§5Just some lore", "second line"),
                ItemLoreLayout.descriptionLines(List.of("§5Just some lore", "second line"), null));
    }
}

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
}

package net.knightsandkings.knk.core.enchantbook;

import net.knightsandkings.knk.core.enchantbook.EnchantBookRules.ApplyResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EnchantBookTextTest {

    @Test
    void teaches_IsPrefixedSoTheCustomLoreParserIgnoresIt() {
        // The custom lore parser reads "<name> <roman>" where <name> is a whole custom display name;
        // "Teaches: Poison" is not one.
        assertEquals("Teaches: Poison II", EnchantBookText.teaches("Poison", 2));
    }

    @Test
    void roman_CoversOneToTenThenDigits() {
        assertEquals("I", EnchantBookText.roman(1));
        assertEquals("IV", EnchantBookText.roman(4));
        assertEquals("X", EnchantBookText.roman(10));
        assertEquals("11", EnchantBookText.roman(11));
        assertEquals("0", EnchantBookText.roman(0));
    }

    @Test
    void displayNameFromKey_TitleCasesTheBareKey() {
        assertEquals("Fire Aspect", EnchantBookText.displayNameFromKey("minecraft:fire_aspect"));
        assertEquals("Flash Chaos", EnchantBookText.displayNameFromKey("flash_chaos"));
        assertEquals("Unknown", EnchantBookText.displayNameFromKey(null));
    }

    @Test
    void gradeLabel() {
        assertEquals("Common ★", EnchantBookText.gradeLabel("Common", 1));
        assertEquals("Epic ★★★★", EnchantBookText.gradeLabel("Epic", 4));
        assertEquals("Mythic", EnchantBookText.gradeLabel("Mythic", null));
        assertEquals("★★", EnchantBookText.gradeLabel(null, 2));
        assertEquals("?", EnchantBookText.gradeLabel(" ", 0));
    }

    @Test
    void cannotApply_Applied_IsEmpty() {
        assertEquals(List.of(), EnchantBookText.cannotApply(ApplyResult.APPLIED, "Sharpness", 3, 0, 1, "Common ★", false, null));
    }

    @Test
    void cannotApply_LevelCapped_ExplainsTheGrade() {
        assertEquals(List.of("Its grade is Common ★.", "It allows Sharpness up to I, which it already has."),
                EnchantBookText.cannotApply(ApplyResult.LEVEL_CAPPED, "Sharpness", 3, 1, 1, "Common ★", false, null));
        assertEquals(List.of("Its grade is Common ★.", "It allows Sharpness up to I; it already has III."),
                EnchantBookText.cannotApply(ApplyResult.LEVEL_CAPPED, "Sharpness", 5, 3, 1, "Common ★", false, null));
        assertEquals(List.of("Its grade is Common ★.", "Unbreaking can't go on items of this grade."),
                EnchantBookText.cannotApply(ApplyResult.LEVEL_CAPPED, "Unbreaking", 1, 0, 0, "Common ★", false, null));
        assertEquals(List.of("It has no grade, so it counts as Common ★.", "Mending can't go on items of this grade."),
                EnchantBookText.cannotApply(ApplyResult.LEVEL_CAPPED, "Mending", 1, 0, 0, "Common ★", true, null));
    }

    @Test
    void cannotApply_ConflictNoImprovementAndOthers() {
        assertEquals(List.of("Conflicts with Smite."),
                EnchantBookText.cannotApply(ApplyResult.CONFLICT, "Sharpness", 3, 0, null, "?", false, "Smite"));
        assertEquals(List.of("Conflicts with an enchantment it already has."),
                EnchantBookText.cannotApply(ApplyResult.CONFLICT, "Sharpness", 3, 0, null, "?", false, null));
        assertEquals(List.of("Already has Sharpness III."),
                EnchantBookText.cannotApply(ApplyResult.NO_IMPROVEMENT, "Sharpness", 3, 3, null, "?", false, null));
        assertEquals(List.of("Already has Poison III; this book is II."),
                EnchantBookText.cannotApply(ApplyResult.NO_IMPROVEMENT, "Poison", 2, 3, null, "?", false, null));
        assertEquals(List.of("That enchantment can't go on this item."),
                EnchantBookText.cannotApply(ApplyResult.NOT_ENCHANTABLE, "Poison", 2, 0, null, "?", false, null));
    }
}

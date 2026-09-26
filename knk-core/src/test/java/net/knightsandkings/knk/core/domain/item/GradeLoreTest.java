package net.knightsandkings.knk.core.domain.item;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GradeLoreTest {

    @Test
    void starsFromLore_ReadsTheMapperLine() {
        // What ItemBlueprintBukkitMapper writes: translateToLegacy("&l&bGrade: ★★★").
        assertEquals(3, GradeLore.starsFromLore(List.of("§7A fine blade", "§l§bGrade: ★★★", "§7Origin: X")).orElseThrow());
        assertEquals(10, GradeLore.starsFromLore(List.of("&l&bGrade: " + "★".repeat(10))).orElseThrow());
    }

    @Test
    void starsFromLore_EmptyWithoutAGradeLine() {
        assertTrue(GradeLore.starsFromLore(null).isEmpty());
        assertTrue(GradeLore.starsFromLore(List.of()).isEmpty());
        assertTrue(GradeLore.starsFromLore(Arrays.asList(null, "§7Poison II")).isEmpty());
        assertTrue(GradeLore.starsFromLore(List.of("§l§bGrade: ")).isEmpty());
    }

    @Test
    void starsFromLore_IgnoresStarsBeforeTheLabel() {
        assertEquals(1, GradeLore.starsFromLore(List.of("★★ shiny ★ Grade: ★")).orElseThrow());
    }
}

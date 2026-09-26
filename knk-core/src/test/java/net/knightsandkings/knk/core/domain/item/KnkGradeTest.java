package net.knightsandkings.knk.core.domain.item;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KnkGradeTest {

    private static KnkGrade grade(int stars) {
        return GradeCatalog.DEFAULTS.get(stars - 1);
    }

    /** v1: {@code maxLevel / (6 - grade)} with int division, for every grade and max level 1-5. "-" = uncapped. */
    @ParameterizedTest(name = "grade {0}: max 1..5 -> {1},{2},{3},{4},{5}")
    @CsvSource({
            "1, 0, 0, 0, 0, 1",
            "2, 0, 0, 0, 1, 1",
            "3, 0, 0, 1, 1, 1",
            "4, 0, 1, 1, 2, 2",
            "5, 1, 2, 3, 4, 5",
            "6, -, -, -, -, -",
            "7, -, -, -, -, -",
            "8, -, -, -, -, -",
            "9, -, -, -, -, -",
            "10, -, -, -, -, -"
    })
    void capEnchantLevel_MatchesV1ForEveryGrade(int stars, String max1, String max2, String max3, String max4, String max5) {
        String[] expected = {max1, max2, max3, max4, max5};
        for (int maxLevel = 1; maxLevel <= 5; maxLevel++) {
            Integer cap = grade(stars).capEnchantLevel(maxLevel);
            String want = expected[maxLevel - 1];
            if (want.equals("-")) {
                assertNull(cap, "grade " + stars + " max " + maxLevel);
            } else {
                assertEquals(Integer.valueOf(want), cap, "grade " + stars + " max " + maxLevel);
            }
        }
    }

    @Test
    void capEnchantLevel_TruncatesLikeV1_NeverRounds() {
        // 4 / 5 = 0.8 would round to 1; v1's int division gives 0.
        assertEquals(0, new KnkGrade(1, "Common", 1, null, 5).capEnchantLevel(4));
        // 3 / 2 = 1.5 would round to 2; v1 gives 1.
        assertEquals(1, new KnkGrade(4, "Epic", 4, null, 2).capEnchantLevel(3));
        // Higher maxima (e.g. a custom definition with max 10): 10 / 3 = 3.
        assertEquals(3, new KnkGrade(3, "Rare", 3, null, 3).capEnchantLevel(10));
    }

    @Test
    void capEnchantLevel_NullOrNonPositiveDivisorIsUncapped() {
        assertNull(new KnkGrade(1, "x", 1).capEnchantLevel(5));
        assertNull(new KnkGrade(1, "x", 1, null, 0).capEnchantLevel(5));
        assertNull(new KnkGrade(1, "x", 1, null, -2).capEnchantLevel(5));
        assertFalse(new KnkGrade(1, "x", 1, null, 0).capsEnchantLevels());
        assertTrue(new KnkGrade(1, "x", 1, null, 1).capsEnchantLevels());
    }

    @Test
    void capEnchantLevel_NegativeMaxLevelCapsAtZero() {
        assertEquals(0, new KnkGrade(1, "x", 1, null, 1).capEnchantLevel(-3));
    }

    @Test
    void threeArgConstructor_LeavesNewFieldsNull() {
        KnkGrade g = new KnkGrade(7, "Rare", 3);
        assertNull(g.dropChance());
        assertNull(g.enchantLevelCapDivisor());
    }
}

package net.knightsandkings.knk.core.enchantbook;

import net.knightsandkings.knk.core.enchantbook.EnchantBookRules.ApplyResult;
import net.knightsandkings.knk.core.enchantbook.EnchantBookRules.Target;
import net.knightsandkings.knk.core.domain.item.GradeCatalog;
import net.knightsandkings.knk.core.domain.item.KnkGrade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class EnchantBookRulesTest {

    private static final EnchantBookPayload SHARPNESS_3 = EnchantBookPayload.vanilla("minecraft:sharpness", 3);

    private static Target ok(int existingLevel) {
        return new Target(true, true, false, existingLevel);
    }

    @Test
    void evaluate_AppliesToCompatibleItemWithoutTheEnchantment() {
        assertEquals(ApplyResult.APPLIED, EnchantBookRules.evaluate(SHARPNESS_3, ok(0)));
    }

    @Test
    void evaluate_AppliesWhenItRaisesTheLevel() {
        assertEquals(ApplyResult.APPLIED, EnchantBookRules.evaluate(SHARPNESS_3, ok(2)));
    }

    @Test
    void evaluate_RefusesSameOrLowerLevel() {
        assertEquals(ApplyResult.NO_IMPROVEMENT, EnchantBookRules.evaluate(SHARPNESS_3, ok(3)));
        assertEquals(ApplyResult.NO_IMPROVEMENT, EnchantBookRules.evaluate(SHARPNESS_3, ok(5)));
    }

    @Test
    void evaluate_InvalidBookWins() {
        assertEquals(ApplyResult.INVALID_BOOK, EnchantBookRules.evaluate(null, ok(0)));
        assertEquals(ApplyResult.INVALID_BOOK, EnchantBookRules.evaluate(null, null));
    }

    @Test
    void evaluate_RefusesUnusableOrIncompatibleTargets() {
        assertEquals(ApplyResult.NOT_ENCHANTABLE, EnchantBookRules.evaluate(SHARPNESS_3, null));
        assertEquals(ApplyResult.NOT_ENCHANTABLE, EnchantBookRules.evaluate(SHARPNESS_3, new Target(false, true, false, 0)));
        assertEquals(ApplyResult.NOT_ENCHANTABLE, EnchantBookRules.evaluate(SHARPNESS_3, new Target(true, false, false, 0)));
    }

    @Test
    void evaluate_IncompatibleBeatsConflict_ConflictBeatsImprovement() {
        assertEquals(ApplyResult.NOT_ENCHANTABLE, EnchantBookRules.evaluate(SHARPNESS_3, new Target(true, false, true, 0)));
        assertEquals(ApplyResult.CONFLICT, EnchantBookRules.evaluate(SHARPNESS_3, new Target(true, true, true, 5)));
    }

    @Test
    void appliedLevel_IsTheMaximum() {
        assertEquals(3, EnchantBookRules.appliedLevel(0, 3));
        assertEquals(4, EnchantBookRules.appliedLevel(4, 3));
    }

    @Test
    void customCompatible_OnlyGear() {
        assertTrue(EnchantBookRules.customCompatible(1561)); // diamond sword
        assertFalse(EnchantBookRules.customCompatible(0));   // bread, blocks, books
    }

    // --- KNG-6 grade cap -------------------------------------------------------------------------------------

    private static final EnchantBookPayload SHARPNESS_1 = EnchantBookPayload.vanilla("minecraft:sharpness", 1);
    private static final EnchantBookPayload SHARPNESS_5 = EnchantBookPayload.vanilla("minecraft:sharpness", 5);
    private static final int SHARPNESS_MAX = 5;

    private static Target graded(int stars, int existingLevel, int maxLevel) {
        KnkGrade grade = GradeCatalog.DEFAULTS.get(stars - 1);
        return new Target(true, true, false, existingLevel, EnchantBookRules.levelCap(grade, maxLevel));
    }

    /**
     * Sharpness (max 5) on a bare item of every grade: caps 1, 1, 1, 2, 5 for grades 1-5, uncapped for 6-10.
     * A Sharpness V book gives the cap; a Sharpness I book always fits (every cap here is at least 1).
     */
    @ParameterizedTest(name = "grade {0}: cap {1}, Sharpness V gives {2}")
    @CsvSource({
            "1, 1, 1",
            "2, 1, 1",
            "3, 1, 1",
            "4, 2, 2",
            "5, 5, 5",
            "6, -, 5",
            "7, -, 5",
            "8, -, 5",
            "9, -, 5",
            "10, -, 5"
    })
    void gradeCap_SharpnessOnEveryGrade(int stars, String cap, int sharpness5Gives) {
        Target bare = graded(stars, 0, SHARPNESS_MAX);
        assertEquals(cap.equals("-") ? null : Integer.valueOf(cap), bare.levelCap());
        assertEquals(ApplyResult.APPLIED, EnchantBookRules.evaluate(SHARPNESS_5, bare));
        assertEquals(sharpness5Gives, EnchantBookRules.appliedLevel(bare, 5));
        assertEquals(sharpness5Gives < 5, EnchantBookRules.cappedBelowBook(bare, 5));
        assertEquals(ApplyResult.APPLIED, EnchantBookRules.evaluate(SHARPNESS_1, bare));
        assertEquals(1, EnchantBookRules.appliedLevel(bare, 1));
    }

    /** An item already at its cap is refused with LEVEL_CAPPED for grades 1-5; 6-10 never are. */
    @ParameterizedTest(name = "grade {0} at level {1}: {2}")
    @CsvSource({
            "1, 1, LEVEL_CAPPED",
            "2, 1, LEVEL_CAPPED",
            "3, 1, LEVEL_CAPPED",
            "4, 2, LEVEL_CAPPED",
            "5, 5, LEVEL_CAPPED",
            "4, 1, APPLIED",
            "5, 4, APPLIED",
            "6, 4, APPLIED",
            "7, 4, APPLIED",
            "8, 4, APPLIED",
            "9, 4, APPLIED",
            "10, 4, APPLIED",
            "10, 5, NO_IMPROVEMENT"
    })
    void gradeCap_ExistingLevelAgainstTheCap(int stars, int existingLevel, ApplyResult expected) {
        assertEquals(expected, EnchantBookRules.evaluate(SHARPNESS_5, graded(stars, existingLevel, SHARPNESS_MAX)));
    }

    @Test
    void gradeCap_ZeroCapMeansTheEnchantmentCantGoOnAtAll() {
        // Unbreaking (max 3) on a 1-star item: 3 / 5 = 0.
        EnchantBookPayload unbreaking1 = EnchantBookPayload.vanilla("minecraft:unbreaking", 1);
        assertEquals(ApplyResult.LEVEL_CAPPED, EnchantBookRules.evaluate(unbreaking1, graded(1, 0, 3)));
        assertEquals(ApplyResult.LEVEL_CAPPED, EnchantBookRules.evaluate(unbreaking1, graded(3, 0, 2)));
        assertEquals(ApplyResult.APPLIED, EnchantBookRules.evaluate(unbreaking1, graded(3, 0, 3)));
    }

    @Test
    void gradeCap_ItemAboveItsCapIsCapped_EvenForALowerBook() {
        // e.g. a vanilla-anvil Sharpness III on a 1-star sword: over the cap, no book helps.
        assertEquals(ApplyResult.LEVEL_CAPPED, EnchantBookRules.evaluate(SHARPNESS_1, graded(1, 3, SHARPNESS_MAX)));
    }

    @Test
    void gradeCap_BelowTheCap_NoImprovementStillApplies() {
        // 5-star, cap 5, item at 3, book I: not capped, just not an improvement.
        assertEquals(ApplyResult.NO_IMPROVEMENT, EnchantBookRules.evaluate(SHARPNESS_1, graded(5, 3, SHARPNESS_MAX)));
    }

    @Test
    void gradeCap_ConflictAndIncompatibleStillWin() {
        assertEquals(ApplyResult.CONFLICT, EnchantBookRules.evaluate(SHARPNESS_5, new Target(true, true, true, 5, 1)));
        assertEquals(ApplyResult.NOT_ENCHANTABLE, EnchantBookRules.evaluate(SHARPNESS_5, new Target(true, false, false, 5, 1)));
    }

    @Test
    void levelCap_NullGradeIsUncapped() {
        assertNull(EnchantBookRules.levelCap(null, 5));
    }

    @Test
    void withBonus_OneMoreLevel_NeverAboveCapOrMax() {
        assertEquals(3, EnchantBookRules.withBonus(2, 5, 5));   // room under the cap
        assertEquals(2, EnchantBookRules.withBonus(2, 2, 5));   // at the cap (Epic Sharpness)
        assertEquals(1, EnchantBookRules.withBonus(1, 1, 5));   // Common Sharpness
        assertEquals(4, EnchantBookRules.withBonus(3, null, 5)); // uncapped: up to max
        assertEquals(5, EnchantBookRules.withBonus(5, null, 5)); // uncapped at max: nothing
        assertEquals(6, EnchantBookRules.withBonus(6, null, 5)); // unsafe book above max: unchanged
    }
}

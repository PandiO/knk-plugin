package net.knightsandkings.knk.core.enchantbook;

import net.knightsandkings.knk.core.enchantbook.EnchantBookRules.ApplyResult;
import net.knightsandkings.knk.core.enchantbook.EnchantBookRules.Target;
import org.junit.jupiter.api.Test;

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
}

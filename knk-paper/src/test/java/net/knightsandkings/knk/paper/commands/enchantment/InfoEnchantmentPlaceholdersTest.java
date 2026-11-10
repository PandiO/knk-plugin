package net.knightsandkings.knk.paper.commands.enchantment;

import net.knightsandkings.knk.core.domain.enchantment.EnchantmentTuning;
import net.knightsandkings.knk.core.domain.enchantment.EnchantmentTuningTable;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** /ce info's combat-value lines: Chaos's blast (#223) and Cleaving's gate strike (#259). */
class InfoEnchantmentPlaceholdersTest {

    @Test
    void cleavingsGateLineAtEachLevel() {
        EnchantmentTuning cleaving = new EnchantmentTuningTableAccess().cleaving();
        assertEquals(Map.of("%gateBonus%", "25", "%strike%", "5", "%strikeCooldown%", "60s"),
                InfoEnchantmentCommand.gatePlaceholders(cleaving, 1));
        assertEquals(Map.of("%gateBonus%", "100", "%strike%", "8", "%strikeCooldown%", "30s"),
                InfoEnchantmentCommand.gatePlaceholders(cleaving, 4));
    }

    @Test
    void chaosAreaLine() {
        EnchantmentTuning chaos = EnchantmentTuningTable.getInstance().builtIn("chaos").orElseThrow();
        assertEquals("40", InfoEnchantmentCommand.areaPlaceholders(chaos, 1).get("%areaDamage%"));
        assertEquals("5", InfoEnchantmentCommand.areaPlaceholders(chaos, 1).get("%effectRadius%"));
    }

    /** The built-in values, whatever another test left as overrides. */
    private static final class EnchantmentTuningTableAccess {
        EnchantmentTuning cleaving() {
            return EnchantmentTuningTable.getInstance().builtIn("cleaving").orElseThrow();
        }
    }
}

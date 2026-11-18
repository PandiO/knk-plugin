package net.knightsandkings.knk.paper.enchantment.effects.impl;

import net.knightsandkings.knk.core.domain.enchantment.EnchantmentTuning;
import net.knightsandkings.knk.core.domain.enchantment.EnchantmentTuningOverride;
import net.knightsandkings.knk.core.domain.enchantment.EnchantmentTuningTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * PandiO/knk-workspace#201: Flash Chaos's Slowness and Nausea come from the combat values, with the old hard-coded
 * numbers (Slowness I 100 ticks, Nausea II 250 ticks) as the defaults. The effect bars (#170) get the same ticks.
 */
class FlashChaosDebuffsTest {

    @AfterEach
    void tearDown() {
        EnchantmentTuningTable.getInstance().clearOverrides();
    }

    @Test
    void defaultsAreTheOldHardCodedValues() {
        FlashChaosEffect.Debuffs debuffs = FlashChaosEffect.Debuffs.of(EnchantmentTuningTable.getInstance().get("flash_chaos"), 1);
        assertEquals(new FlashChaosEffect.Debuffs(100, 0, 250), debuffs);
        assertEquals(1, FlashChaosEffect.NAUSEA_AMPLIFIER);
    }

    @Test
    void anAdminOverrideChangesDurationsAndSlownessLevel() {
        EnchantmentTuningTable.getInstance().replaceOverrides(List.of(
                new EnchantmentTuningOverride("knk:flash_chaos", null, null, null, null, 3d, null, 2, 7.5d)));

        FlashChaosEffect.Debuffs debuffs = FlashChaosEffect.Debuffs.of(EnchantmentTuningTable.getInstance().get("flash_chaos"), 1);
        assertEquals(new FlashChaosEffect.Debuffs(60, 1, 150), debuffs);
    }

    @Test
    void aZeroDurationLeavesThatDebuffOut() {
        EnchantmentTuning tuning = new EnchantmentTuning(1.0d, 0.0d, true, 90_000L, 0, 0.0d, 1, 250);
        FlashChaosEffect.Debuffs debuffs = FlashChaosEffect.Debuffs.of(tuning, 1);
        assertEquals(0, debuffs.slownessTicks());
        assertEquals(250, debuffs.nauseaTicks());
    }
}

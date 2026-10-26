package net.knightsandkings.knk.core.domain.enchantment;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PandiO/knk-workspace#151: admin-configurable combat values per custom enchantment. */
class EnchantmentTuningTableTest {

    private static EnchantmentTuningOverride override(String key, Double chance, Double perLevel, Boolean cooldownOn,
                                                      Integer cooldownSeconds, Double durationSeconds, Double multiplier) {
        return new EnchantmentTuningOverride(key, chance, perLevel, cooldownOn, cooldownSeconds, durationSeconds, multiplier);
    }

    @Test
    void everyEffectOfTheRegistryHasBuiltInValuesExceptCleaving() {
        EnchantmentTuningTable table = new EnchantmentTuningTable();
        for (Enchantment enchantment : EnchantmentRegistry.getInstance().getAll()) {
            boolean expected = !"cleaving".equals(enchantment.id());
            assertEquals(expected, table.builtIn(enchantment.id()).isPresent(), enchantment.id());
        }
    }

    /** PandiO/knk-workspace#156; knk-web-api's EnchantmentCombatDefaultsSeedTests pins the same numbers. */
    @Test
    void builtInValuesAreTheWeakerDefaults() {
        EnchantmentTuningTable table = new EnchantmentTuningTable();
        for (String id : List.of("poison", "wither", "confusion", "freeze")) {
            EnchantmentTuning tuning = table.get(id);
            assertEquals(0.10d, tuning.chanceAt(1), 1e-9, id);
            assertEquals(0.20d, tuning.chanceAt(3), 1e-9, id);
            assertTrue(tuning.cooldownEnabled(), id);
        }
        assertEquals(List.of(60, 90, 120), durations(table.get("poison")));
        assertEquals(List.of(60, 90, 120), durations(table.get("confusion")));
        assertEquals(List.of(40, 60, 80), durations(table.get("wither")));
        assertEquals(8_000L, table.get("poison").effectiveCooldownMs());
        // Freeze: 1.5 / 2.25 / 3 s, then 20 s before the same attacker can freeze again.
        assertEquals(List.of(30, 45, 60), durations(table.get("freeze")));
        assertEquals(20_000L, table.get("freeze").effectiveCooldownMs());
        // Blindness no longer on every hit: 20 / 30 / 40 %, 2 / 3 / 4 s, 10 s cooldown.
        assertEquals(0.40d, table.get("blindness").chanceAt(3), 1e-9);
        assertEquals(List.of(40, 60, 80), durations(table.get("blindness")));
        assertEquals(10_000L, table.get("blindness").effectiveCooldownMs());
        // Unchanged: strength and the right-click abilities.
        assertEquals(0.45d, table.get("strength").chanceAt(3), 1e-9);
        assertEquals(300, table.get("strength").durationTicksAt(2));
        assertEquals(120_000L, table.get("strength").effectiveCooldownMs());
        assertEquals(90_000L, table.get("chaos").effectiveCooldownMs());
        assertEquals(90_000L, table.get("flash_chaos").effectiveCooldownMs());
        assertEquals(90_000L, table.get("invisibility").effectiveCooldownMs());
        assertEquals(120_000L, table.get("health_boost").effectiveCooldownMs());
        assertEquals(120_000L, table.get("armor_repair").effectiveCooldownMs());
        assertEquals(List.of(200, 300, 400), durations(table.get("resistance")));
        assertEquals(200, table.get("invisibility").durationTicksAt(1));
    }

    private static List<Integer> durations(EnchantmentTuning tuning) {
        return List.of(tuning.durationTicksAt(1), tuning.durationTicksAt(2), tuning.durationTicksAt(3));
    }

    @Test
    void chanceAndDurationScalePerLevel() {
        EnchantmentTuning tuning = new EnchantmentTuning(0.10d, 0.05d, true, 20_000L, 30, 0.5d);

        assertEquals(0.0d, tuning.chanceAt(0), 1e-9);
        assertEquals(0.10d, tuning.chanceAt(1), 1e-9);
        assertEquals(0.20d, tuning.chanceAt(3), 1e-9);
        assertEquals(30, tuning.durationTicksAt(1));
        assertEquals(45, tuning.durationTicksAt(2));
        assertEquals(60, tuning.durationTicksAt(3));
        assertEquals(20_000L, tuning.effectiveCooldownMs());
    }

    @Test
    void chanceIsKeptBetweenZeroAndOne() {
        assertEquals(1.0d, new EnchantmentTuning(0.6d, 0.6d, false, 0L, 0, 0d).chanceAt(3), 1e-9);
        assertEquals(0.0d, new EnchantmentTuning(0.1d, -0.2d, false, 0L, 0, 0d).chanceAt(3), 1e-9);
    }

    @Test
    void aSwitchedOffCooldownKeepsItsLength() {
        EnchantmentTuning tuning = new EnchantmentTuning(1d, 0d, false, 30_000L, 0, 0d);
        assertEquals(0L, tuning.effectiveCooldownMs());
        assertEquals(30_000L, tuning.cooldownMs());
    }

    @Test
    void anOverrideReplacesOnlyTheValuesItSets() {
        EnchantmentTuningTable table = new EnchantmentTuningTable();
        EnchantmentTuning before = table.get("freeze");

        int matched = table.replaceOverrides(List.of(override("knk:freeze", 10d, null, true, 20, null, null)));

        EnchantmentTuning after = table.get("freeze");
        assertEquals(1, matched);
        assertEquals(0.10d, after.procChance(), 1e-9);
        assertEquals(before.procChancePerLevel(), after.procChancePerLevel(), 1e-9);
        assertTrue(after.cooldownEnabled());
        assertEquals(20_000L, after.effectiveCooldownMs());
        assertEquals(before.durationTicks(), after.durationTicks());
        assertEquals(before.levelMultiplier(), after.levelMultiplier(), 1e-9);
        assertTrue(table.override("freeze").isPresent());
    }

    @Test
    void secondsBecomeTicksAndPercentBecomesAFraction() {
        EnchantmentTuningTable table = new EnchantmentTuningTable();
        table.replaceOverrides(List.of(override("Flash Chaos", null, null, false, 45, null, null),
                override("poison", 12.5d, 2.5d, null, null, 1.5d, 0d)));

        EnchantmentTuning poison = table.get("poison");
        assertEquals(0.125d, poison.chanceAt(1), 1e-9);
        assertEquals(0.175d, poison.chanceAt(3), 1e-9);
        assertEquals(30, poison.durationTicksAt(1));
        assertEquals(30, poison.durationTicksAt(3));
        assertEquals(0L, table.get("flash_chaos").effectiveCooldownMs());
        assertEquals(45_000L, table.get("flash_chaos").cooldownMs());
    }

    @Test
    void aDefinitionNoLongerListedFallsBackToTheBuiltInValues() {
        EnchantmentTuningTable table = new EnchantmentTuningTable();
        table.replaceOverrides(List.of(override("freeze", 50d, 0d, null, null, null, null)));
        table.replaceOverrides(List.of());

        assertEquals(table.builtIn("freeze").orElseThrow(), table.get("freeze"));
        assertFalse(table.override("freeze").isPresent());
    }

    @Test
    void unknownKeysAndVanillaKeysAreIgnored() {
        EnchantmentTuningTable table = new EnchantmentTuningTable();
        int matched = table.replaceOverrides(List.of(override("minecraft:sharpness", 50d, null, null, null, null, null),
                override("lifesteal", 50d, null, null, null, null, null)));
        assertEquals(0, matched);
        assertSame(EnchantmentTuning.NONE, table.get("lifesteal"));
    }

    @Test
    void outOfRangeValuesAreClamped() {
        EnchantmentTuningTable table = new EnchantmentTuningTable();
        table.replaceOverrides(List.of(override("wither", 250d, -300d, true, -5, -1d, 99d)));

        EnchantmentTuning wither = table.get("wither");
        assertEquals(1.0d, wither.procChance(), 1e-9);
        assertEquals(-1.0d, wither.procChancePerLevel(), 1e-9);
        assertEquals(0L, wither.cooldownMs());
        assertEquals(0, wither.durationTicks());
        assertEquals(10.0d, wither.levelMultiplier(), 1e-9);
    }

    @Test
    void matchKeyIgnoresNamespaceCaseAndSeparators() {
        assertEquals("flashchaos", EnchantmentTuningTable.matchKey("knk:Flash_Chaos"));
        assertEquals("healthboost", EnchantmentTuningTable.matchKey(" health boost "));
        assertEquals("", EnchantmentTuningTable.matchKey(null));
    }
}

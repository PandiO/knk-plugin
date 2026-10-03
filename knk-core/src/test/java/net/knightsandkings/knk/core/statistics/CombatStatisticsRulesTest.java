package net.knightsandkings.knk.core.statistics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

/** Damage cap, CUSTOM exclusion, PvE spawn-reason filter and arrow filter (DESIGN.md §F.7, L1-9, L1-10). */
class CombatStatisticsRulesTest {

    @Test
    void damageIsCappedAtTheVictimsHealthPlusAbsorption() {
        assertEquals(6.0, CombatStatisticsRules.cappedDamage(6.0, 20.0, 0.0));
        assertEquals(5.0, CombatStatisticsRules.cappedDamage(30.0, 3.0, 2.0), "overkill counts what was left");
        assertEquals(4.0, CombatStatisticsRules.cappedDamage(4.0, 3.0, 2.0));
    }

    @Test
    void invalidOrNonPositiveDamageCountsNothing() {
        assertEquals(0.0, CombatStatisticsRules.cappedDamage(0.0, 20.0, 0.0));
        assertEquals(0.0, CombatStatisticsRules.cappedDamage(-3.0, 20.0, 0.0));
        assertEquals(0.0, CombatStatisticsRules.cappedDamage(Double.NaN, 20.0, 0.0));
        assertEquals(0.0, CombatStatisticsRules.cappedDamage(Double.POSITIVE_INFINITY, 20.0, 0.0));
        assertEquals(0.0, CombatStatisticsRules.cappedDamage(5.0, 0.0, 0.0), "a dead victim has nothing left");
        assertEquals(2.0, CombatStatisticsRules.cappedDamage(5.0, Double.NaN, 2.0));
        assertEquals(5.0, CombatStatisticsRules.cappedDamage(5.0, 10.0, -4.0), "negative absorption is ignored");
    }

    @Test
    void customDamageIsExcludedUnlessConfigured() {
        assertFalse(CombatStatisticsRules.countsDamageCause("CUSTOM", false));
        assertFalse(CombatStatisticsRules.countsDamageCause("custom", false));
        assertTrue(CombatStatisticsRules.countsDamageCause("CUSTOM", true));
        assertTrue(CombatStatisticsRules.countsDamageCause("ENTITY_ATTACK", false));
        assertTrue(CombatStatisticsRules.countsDamageCause("PROJECTILE", false));
        assertTrue(CombatStatisticsRules.countsDamageCause(null, false));
    }

    @Test
    void farmedCreaturesAreNotPveKills() {
        Set<String> excluded = Set.copyOf(CombatStatisticsRules.DEFAULT_PVE_EXCLUDED_SPAWN_REASONS);
        assertFalse(CombatStatisticsRules.countsPveKill("SPAWNER", excluded));
        assertFalse(CombatStatisticsRules.countsPveKill("spawner_egg", excluded));
        assertFalse(CombatStatisticsRules.countsPveKill("BREEDING", excluded));
        assertFalse(CombatStatisticsRules.countsPveKill("EGG", excluded));
        assertFalse(CombatStatisticsRules.countsPveKill("DISPENSE_EGG", excluded));
        assertTrue(CombatStatisticsRules.countsPveKill("NATURAL", excluded));
        assertTrue(CombatStatisticsRules.countsPveKill("CHUNK_GEN", excluded));
        assertTrue(CombatStatisticsRules.countsPveKill(null, excluded));
        assertTrue(CombatStatisticsRules.countsPveKill("SPAWNER", Set.of()), "an empty list excludes nothing");
        assertTrue(CombatStatisticsRules.countsPveKill("SPAWNER", null));
    }

    @Test
    void onlyArrowProjectilesAreArrowsFired() {
        assertTrue(CombatStatisticsRules.isArrow(true, false));
        assertFalse(CombatStatisticsRules.isArrow(true, true), "tridents are arrows in Bukkit's hierarchy but not here");
        assertFalse(CombatStatisticsRules.isArrow(false, false), "fireworks from a crossbow");
    }
}

package net.knightsandkings.knk.core.statistics;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Siege Survival link S3 (PandiO/knk-workspace#41): kills of Survival match NPCs, damage with them and deaths to them
 * stay out of the ordinary PvE statistics; every ordinary creature classifies as before.
 */
class MatchNpcStatisticsTest {

    private static final Set<String> EXCLUDED = Set.copyOf(CombatStatisticsRules.DEFAULT_PVE_EXCLUDED_SPAWN_REASONS);

    @Test
    void anOrdinaryCreatureCountsAsBefore() {
        assertTrue(CombatStatisticsRules.countsPveKill("NATURAL", EXCLUDED, CreatureClass.ORDINARY));
        assertTrue(CombatStatisticsRules.countsPveKill(null, EXCLUDED, CreatureClass.ORDINARY));
        assertFalse(CombatStatisticsRules.countsPveKill("SPAWNER", EXCLUDED, CreatureClass.ORDINARY));
        assertTrue(CombatStatisticsRules.countsCreatureDamage(CreatureClass.ORDINARY));
        assertTrue(CombatStatisticsRules.countsCreatureDamage(null), "unclassified = ordinary");
    }

    @Test
    void aMatchNpcNeverCountsAsAPveKillWhateverItsSpawnReason() {
        assertFalse(CombatStatisticsRules.countsPveKill("NATURAL", EXCLUDED, CreatureClass.MATCH_NPC));
        assertFalse(CombatStatisticsRules.countsPveKill("CUSTOM", EXCLUDED, CreatureClass.MATCH_NPC));
        assertFalse(CombatStatisticsRules.countsPveKill(null, Set.of(), CreatureClass.MATCH_NPC));
        assertFalse(CombatStatisticsRules.countsCreatureDamage(CreatureClass.MATCH_NPC));
    }

    @Test
    void creatureClassOfABoolean() {
        assertEquals(CreatureClass.MATCH_NPC, CreatureClass.of(true));
        assertEquals(CreatureClass.ORDINARY, CreatureClass.of(false));
    }

    @Test
    void aDeathToAMatchNpcHasNoRecordedCause() {
        DeathCauseClassifier.Cause cause = DeathCauseClassifier.classify(false, DeathCauseClassifier.LastDamager.MATCH_NPC);
        assertEquals(DeathCauseClassifier.Cause.MATCH_NPC, cause);
        assertNull(cause.metric());
        assertFalse(cause.recorded());
    }

    @Test
    void aPlayerKillerStillWinsOverAMatchNpcLastHit() {
        assertEquals(DeathCauseClassifier.Cause.PLAYER,
                DeathCauseClassifier.classify(true, DeathCauseClassifier.LastDamager.MATCH_NPC));
    }

    @Test
    void theOrdinaryCausesAreRecorded() {
        for (DeathCauseClassifier.Cause cause : DeathCauseClassifier.Cause.values()) {
            assertEquals(cause != DeathCauseClassifier.Cause.MATCH_NPC, cause.recorded(), cause.name());
        }
        assertEquals(DeathCauseClassifier.Cause.MOB, DeathCauseClassifier.classify(false, DeathCauseClassifier.LastDamager.MOB));
        assertEquals(DeathCauseClassifier.Cause.ENVIRONMENT, DeathCauseClassifier.classify(false, DeathCauseClassifier.LastDamager.NONE));
    }
}

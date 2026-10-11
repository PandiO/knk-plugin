package net.knightsandkings.knk.core.statistics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.statistics.DeathCauseClassifier.Cause;
import net.knightsandkings.knk.core.statistics.DeathCauseClassifier.LastDamager;

/** Death causes (DESIGN.md §F.7): player killer → player, else last entity damage → mob, else environment. */
class DeathCauseClassifierTest {

    @Test
    void aPlayerKillerIsAPlayerDeathWhateverTheLastDamage() {
        assertEquals(Cause.PLAYER, DeathCauseClassifier.classify(true, LastDamager.NONE));
        assertEquals(Cause.PLAYER, DeathCauseClassifier.classify(true, LastDamager.MOB));
        assertEquals(Cause.PLAYER, DeathCauseClassifier.classify(true, LastDamager.PLAYER));
    }

    @Test
    void lastDamageByAnotherPlayerWithoutKillCreditIsStillAPlayerDeath() {
        assertEquals(Cause.PLAYER, DeathCauseClassifier.classify(false, LastDamager.PLAYER));
    }

    @Test
    void lastDamageByACreatureIsAMobDeath() {
        assertEquals(Cause.MOB, DeathCauseClassifier.classify(false, LastDamager.MOB));
    }

    @Test
    void everythingElseIsTheEnvironment() {
        assertEquals(Cause.ENVIRONMENT, DeathCauseClassifier.classify(false, LastDamager.NONE));
        assertEquals(Cause.ENVIRONMENT, DeathCauseClassifier.classify(false, null));
    }

    @Test
    void causesMapToTheInternalCounters() {
        assertEquals(StatisticsMetric.DEATHS_BY_CAUSE_PLAYER, Cause.PLAYER.metric());
        assertEquals(StatisticsMetric.DEATHS_BY_CAUSE_MOB, Cause.MOB.metric());
        assertEquals(StatisticsMetric.DEATHS_BY_CAUSE_ENVIRONMENT, Cause.ENVIRONMENT.metric());
    }
}

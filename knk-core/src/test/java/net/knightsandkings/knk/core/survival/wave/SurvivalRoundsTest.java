package net.knightsandkings.knk.core.survival.wave;

import net.knightsandkings.knk.core.survival.SurvivalSubstate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static net.knightsandkings.knk.core.survival.wave.RoundSpawnQueueTest.planned;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DESIGN §3 steps 3–7: the round lifecycle. */
class SurvivalRoundsTest {

    @Test
    void lifecycleFromPreparingThroughIntermissionToNextWave() {
        SurvivalRounds rounds = new SurvivalRounds(10_000);
        assertEquals(SurvivalSubstate.PREPARING, rounds.substate());
        assertEquals(1, rounds.nextRound());
        assertFalse(rounds.deathsCountTowardWipe());

        RoundSpawnQueue wave1 = rounds.startWave(planned(1, 2), 0);
        assertEquals(SurvivalSubstate.WAVE_ACTIVE, rounds.substate());
        assertTrue(rounds.deathsCountTowardWipe());
        assertEquals(new WaveRecord(1, 0), rounds.record());

        assertTrue(rounds.completeWaveIfDone(100).isEmpty(), "not complete yet");
        wave1.nextSpawn(0);
        wave1.nextSpawn(0);
        wave1.recordDeath(1001, KillCause.PLAYER);
        wave1.recordDeath(1002, KillCause.SYSTEM);
        Intermission intermission = rounds.completeWaveIfDone(5_000).orElseThrow();
        assertEquals(SurvivalSubstate.INTERMISSION, rounds.substate());
        assertEquals(new WaveRecord(1, 1), rounds.record());
        assertEquals(15_000, intermission.endMillis());
        assertFalse(rounds.deathsCountTowardWipe(), "S41: break deaths never count toward a wipe");
        assertFalse(rounds.intermissionOver(14_999));
        assertTrue(rounds.intermissionOver(15_000));

        rounds.startWave(planned(2, 1), 0);
        assertEquals(2, rounds.currentRound());
        assertEquals(new WaveRecord(2, 1), rounds.record());
        assertTrue(rounds.intermission().isEmpty());
    }

    @Test
    void cannotStartAWaveWhileOneIsActiveOrAfterEnding() {
        SurvivalRounds rounds = SurvivalRounds.withDefaultIntermission();
        rounds.startWave(planned(1, 1), 0);
        assertThrows(IllegalStateException.class, () -> rounds.startWave(planned(2, 1), 0));
        rounds.end();
        assertThrows(IllegalStateException.class, () -> rounds.startWave(planned(2, 1), 0));
    }

    @Test
    void endCancelsRemainingSpawnsAndKeepsReachedWithoutCompleted() {
        SurvivalRounds rounds = SurvivalRounds.withDefaultIntermission();
        RoundSpawnQueue wave = rounds.startWave(planned(1, 3), 1);
        wave.nextSpawn(0);
        List<PlannedEnemy> cancelled = rounds.end();
        assertEquals(2, cancelled.size());
        assertEquals(SurvivalSubstate.ENDING, rounds.substate());
        assertEquals(new WaveRecord(1, 0), rounds.record());
        assertTrue(rounds.completeWaveIfDone(0).isEmpty());
    }

    @Test
    void plannedEnemiesMustBelongToTheNextRound() {
        SurvivalRounds rounds = SurvivalRounds.withDefaultIntermission();
        assertThrows(IllegalArgumentException.class, () -> rounds.startWave(planned(2, 1), 0));
    }
}

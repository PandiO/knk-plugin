package net.knightsandkings.knk.paper.siege.survival;

import net.knightsandkings.knk.core.survival.SurvivalSubstate;
import net.knightsandkings.knk.paper.siege.survival.GhostRules.Release;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GhostRulesTest {

    @Test
    void waveDeathsAreGhostsUntilTheIntermissionBreakDeathsUntilTheNextRound() {
        assertEquals(Release.INTERMISSION_START, GhostRules.releaseFor(SurvivalSubstate.WAVE_ACTIVE));
        assertEquals(Release.NEXT_ROUND_START, GhostRules.releaseFor(SurvivalSubstate.INTERMISSION));
        assertEquals(Release.NONE, GhostRules.releaseFor(SurvivalSubstate.PREPARING));
        assertEquals(Release.NONE, GhostRules.releaseFor(SurvivalSubstate.ENDING));
        assertTrue(GhostRules.becomesGhost(SurvivalSubstate.WAVE_ACTIVE));
        assertFalse(GhostRules.becomesGhost(SurvivalSubstate.PREPARING));
    }

    @Test
    void transitionsReleaseTheRightGhosts() {
        assertTrue(GhostRules.releasedBy(Release.INTERMISSION_START, SurvivalSubstate.INTERMISSION));
        assertFalse(GhostRules.releasedBy(Release.INTERMISSION_START, SurvivalSubstate.WAVE_ACTIVE));
        assertTrue(GhostRules.releasedBy(Release.NEXT_ROUND_START, SurvivalSubstate.WAVE_ACTIVE));
        assertFalse(GhostRules.releasedBy(Release.NEXT_ROUND_START, SurvivalSubstate.INTERMISSION));
        assertFalse(GhostRules.releasedBy(Release.NONE, SurvivalSubstate.INTERMISSION));
    }

    @Test
    void aLateRespawnClickIsAliveWhenTheReleaseMomentPassed() {
        assertFalse(GhostRules.respawnsAlive(Release.INTERMISSION_START, SurvivalSubstate.WAVE_ACTIVE));
        assertTrue(GhostRules.respawnsAlive(Release.INTERMISSION_START, SurvivalSubstate.INTERMISSION));
        assertFalse(GhostRules.respawnsAlive(Release.NEXT_ROUND_START, SurvivalSubstate.INTERMISSION));
        assertTrue(GhostRules.respawnsAlive(Release.NEXT_ROUND_START, SurvivalSubstate.WAVE_ACTIVE));
        assertTrue(GhostRules.respawnsAlive(Release.NONE, SurvivalSubstate.PREPARING));
    }
}

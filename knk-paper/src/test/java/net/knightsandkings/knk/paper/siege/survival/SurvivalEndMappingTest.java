package net.knightsandkings.knk.paper.siege.survival;

import net.knightsandkings.knk.core.domain.siege.SiegeEndReason;
import net.knightsandkings.knk.core.siege.WinResolver;
import net.knightsandkings.knk.core.survival.end.SurvivalEndReason;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurvivalEndMappingTest {

    @Test
    void survivalLossesEndAsTeamEliminatedWithTheNpcAllianceWinning() {
        for (SurvivalEndReason r : new SurvivalEndReason[] {SurvivalEndReason.TEAM_WIPE, SurvivalEndReason.ALL_OBJECTIVES_CAPTURED}) {
            SiegeEndReason siege = SurvivalEndMapping.siegeReason(r);
            assertEquals(SiegeEndReason.TEAM_ELIMINATED, siege);
            WinResolver.Result result = SurvivalEndMapping.result(r, siege, 2);
            assertEquals(2, result.winningAllianceGroup().orElseThrow());
            assertEquals(WinResolver.Decision.LAST_ALLIANCE_STANDING, result.decision());
        }
        WinResolver.Result insta = SurvivalEndMapping.result(SurvivalEndReason.INSTA_LOSE_OBJECTIVE_CAPTURED, SiegeEndReason.INSTANT_VICTORY, 2);
        assertEquals(WinResolver.Decision.INSTANT_VICTORY_CAPTURE, insta.decision());
        assertEquals(2, insta.winningAllianceGroup().orElseThrow());
    }

    @Test
    void noPlayersLeftIsADrawAndAbortsStayAborted() {
        WinResolver.Result none = SurvivalEndMapping.result(SurvivalEndReason.NO_PLAYERS_LEFT, SiegeEndReason.TEAM_ELIMINATED, 2);
        assertTrue(none.isDraw());
        assertTrue(none.winningAllianceGroup().isEmpty());
        WinResolver.Result abort = SurvivalEndMapping.result(SurvivalEndReason.SERVER_RESTART, SiegeEndReason.SERVER_RESTART, 2);
        assertTrue(abort.isAborted());
        assertEquals(SiegeEndReason.ADMIN_STOPPED, SurvivalEndMapping.siegeReason(SurvivalEndReason.ADMIN_ABORT));
    }

    @Test
    void siegeEndingsTheRuntimeDidNotTriggerMapBack() {
        assertEquals(SurvivalEndReason.ADMIN_ABORT, SurvivalEndMapping.survivalReason(SiegeEndReason.ADMIN_STOPPED, true));
        assertEquals(SurvivalEndReason.INSTA_LOSE_OBJECTIVE_CAPTURED, SurvivalEndMapping.survivalReason(SiegeEndReason.INSTANT_VICTORY, true));
        assertEquals(SurvivalEndReason.NO_PLAYERS_LEFT, SurvivalEndMapping.survivalReason(SiegeEndReason.TEAM_ELIMINATED, false));
        assertEquals(SurvivalEndReason.TEAM_WIPE, SurvivalEndMapping.survivalReason(SiegeEndReason.TEAM_ELIMINATED, true));
    }
}

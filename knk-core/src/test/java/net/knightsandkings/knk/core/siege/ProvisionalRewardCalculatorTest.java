package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeRewards;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.domain.siege.SiegeEndReason;
import net.knightsandkings.knk.core.domain.siege.SiegeTeamRole;
import net.knightsandkings.knk.core.siege.ProvisionalRewardCalculator.Breakdown;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static net.knightsandkings.knk.core.siege.SiegeTestData.CONFIG;
import static net.knightsandkings.knk.core.siege.SiegeTestData.at;
import static net.knightsandkings.knk.core.siege.SiegeTestData.objective;
import static net.knightsandkings.knk.core.siege.SiegeTestData.player;
import static net.knightsandkings.knk.core.siege.SiegeTestData.team;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Siege Phase 5: the provisional in-game reward breakdown (DESIGN §7.6), shown until Phase 6 returns the real one. */
class ProvisionalRewardCalculatorTest {

    private static final int DEF = 1;
    private static final int ATT = 2;
    private static final KnkSiegeRewards REWARDS = new KnkSiegeRewards(100, 10, 1, 50, 5, 30, 3);

    private final KnkSiegeScenario scenario = SiegeTestData.scenario(1, 5,
            List.of(team(DEF, SiegeTeamRole.DEFENDER, 1, "D"), team(ATT, SiegeTeamRole.ATTACKER, 2, "A")),
            List.of(objective(1, true, DEF), objective(2, false, DEF), objective(3, false, DEF)), true);
    private final AllianceResolver alliances = AllianceResolver.of(scenario);

    private SiegeObjectiveBoard board() {
        return new SiegeObjectiveBoard(scenario, new CaptureCalculator(CONFIG), alliances);
    }

    private static void captureWith(SiegeObjectiveBoard board, int objectiveId, int playerNumber, int teamId) {
        for (int i = 0; i < 1000; i++) {
            if (!board.step(Map.of(objectiveId, List.of(at(playerNumber, teamId, 1.0)))).captures().isEmpty()) return;
        }
        throw new AssertionError("no capture");
    }

    @Test
    void winnerGetsWinPlusHoldingPlusPersonalCaptures() {
        SiegeObjectiveBoard board = board();
        captureWith(board, 2, 11, ATT);
        captureWith(board, 3, 12, ATT);
        WinResolver.Result result = new WinResolver.Result(SiegeEndReason.TIME_EXPIRED, java.util.OptionalInt.of(2),
                WinResolver.Decision.MOST_OBJECTIVES);

        Breakdown capturer = ProvisionalRewardCalculator.forParticipant(REWARDS, result, alliances, board, player(11), ATT);
        assertTrue(capturer.won());
        assertEquals(2, capturer.holdingCount());
        assertEquals(1, capturer.captureCount());
        assertEquals(100 + 2 * 50 + 30, capturer.coins());
        assertEquals(10 + 2 * 5 + 3, capturer.experience());
        assertEquals(1, capturer.gems());

        Breakdown loser = ProvisionalRewardCalculator.forParticipant(REWARDS, result, alliances, board, player(1), DEF);
        assertFalse(loser.won());
        assertEquals(0, loser.holdingCount(), "defenders held these at the start: no holding reward");
        assertTrue(loser.isEmpty());
    }

    @Test
    void recapturePingPongCountsEachObjectiveOnceForTheCapturer() {
        SiegeObjectiveBoard board = board();
        captureWith(board, 2, 11, ATT);
        captureWith(board, 2, 1, DEF);
        captureWith(board, 2, 11, ATT);
        WinResolver.Result draw = new WinResolver.Result(SiegeEndReason.TIME_EXPIRED, java.util.OptionalInt.empty(),
                WinResolver.Decision.DRAW);

        Breakdown b = ProvisionalRewardCalculator.forParticipant(REWARDS, draw, alliances, board, player(11), ATT);
        assertFalse(b.won());
        assertEquals(1, b.captureCount());
        assertEquals(1, b.holdingCount());
        assertEquals(50 + 30, b.coins());
        assertEquals(0, b.gems(), "no win reward in a draw");
    }

    @Test
    void anAbortedMatchPaysNothing() {
        SiegeObjectiveBoard board = board();
        captureWith(board, 2, 11, ATT);
        WinResolver.Result aborted = new WinResolver.Result(SiegeEndReason.ADMIN_STOPPED, java.util.OptionalInt.empty(),
                WinResolver.Decision.ABORTED);
        assertEquals(Breakdown.NONE, ProvisionalRewardCalculator.forParticipant(REWARDS, aborted, alliances, board, player(11), ATT));
    }
}

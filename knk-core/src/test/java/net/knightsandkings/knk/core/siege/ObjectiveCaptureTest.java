package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.domain.siege.SiegeTeamRole;
import net.knightsandkings.knk.core.siege.ObjectiveState.CaptureEvent;
import net.knightsandkings.knk.core.siege.ObjectiveState.Presence;
import net.knightsandkings.knk.core.siege.ObjectiveState.StepResult;
import net.knightsandkings.knk.core.siege.SiegeObjectiveBoard.BoardStep;
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

/**
 * Siege Phase 4: per-objective capture (DESIGN §7.1–7.3), side-capture pressure (§7.4) and
 * recapture (D5).
 */
class ObjectiveCaptureTest {

    private static final int DEF = 1;
    private static final int ATT = 2;
    private static final int KEEP = 1;
    private static final int GATE = 2;
    private static final int WALL = 3;

    private final CaptureCalculator calculator = new CaptureCalculator(CONFIG);

    private static KnkSiegeScenario scenario(boolean allowRecapture) {
        return SiegeTestData.scenario(1, 5,
                List.of(team(DEF, SiegeTeamRole.DEFENDER, 1, "Defenders"), team(ATT, SiegeTeamRole.ATTACKER, 2, "Attackers")),
                List.of(objective(KEEP, true, DEF), objective(GATE, false, DEF), objective(WALL, false, DEF)),
                allowRecapture);
    }

    private SiegeObjectiveBoard board(KnkSiegeScenario scenario) {
        return new SiegeObjectiveBoard(scenario, calculator, AllianceResolver.of(scenario));
    }

    /** Steps until the objective is captured; returns the capture step. */
    private static BoardStep stepUntilCaptured(SiegeObjectiveBoard board, int objectiveId, List<Presence> present) {
        for (int i = 0; i < 10_000; i++) {
            BoardStep step = board.step(Map.of(objectiveId, present));
            if (step.captures().stream().anyMatch(c -> c.objectiveId() == objectiveId)) return step;
        }
        throw new AssertionError("objective " + objectiveId + " never captured");
    }

    @Test
    void aLoneAttackerCapturesInOneHundredSecondsAndTheClosestAttackerIsCredited() {
        SiegeObjectiveBoard board = board(scenario(false));
        List<Presence> present = List.of(at(11, ATT, 2.0), at(12, ATT, 0.5), at(1, DEF, 1.0));
        // 2 attackers vs 1 defender on a side objective: 7 - 6 = 1 point per second.
        StepResult first = board.step(Map.of(GATE, present)).steps().get(1);
        assertEquals(499, first.pointsAfter());
        assertEquals(2, first.attackers());
        assertEquals(1, first.defenders());
        assertTrue(first.contested());

        BoardStep capture = stepUntilCaptured(board, GATE, present);
        CaptureEvent event = capture.captures().get(0);
        assertEquals(player(12), event.capturerId(), "closest living attacker in the radius (legacy calculateCapturer)");
        assertEquals(DEF, event.previousHolderTeamId());
        assertEquals(ATT, event.newHolderTeamId());
        assertTrue(event.firstCapture());
        assertTrue(event.rewardEligible());
        assertEquals(ATT, board.objective(GATE).holderTeamId());
    }

    @Test
    void defendersRestorePointsUpToTheMaximum() {
        SiegeObjectiveBoard board = board(scenario(false));
        for (int i = 0; i < 10; i++) board.step(Map.of(GATE, List.of(at(11, ATT, 1))));
        assertEquals(450, board.objective(GATE).points());

        board.step(Map.of(GATE, List.of(at(1, DEF, 1), at(2, DEF, 1))));
        assertEquals(459, board.objective(GATE).points());
        for (int i = 0; i < 20; i++) board.step(Map.of(GATE, List.of(at(1, DEF, 1))));
        assertEquals(500, board.objective(GATE).points());
        assertFalse(board.objective(GATE).isContested());
    }

    @Test
    void recaptureOffCapturedObjectiveIsFinalAndStopsScoring() {
        SiegeObjectiveBoard board = board(scenario(false));
        stepUntilCaptured(board, GATE, List.of(at(11, ATT, 1)));
        ObjectiveState gate = board.objective(GATE);
        assertTrue(gate.isCapturedFinal());
        assertEquals(0, gate.points());

        // The former holders standing on it change nothing: legacy "captured is final".
        for (int i = 0; i < 50; i++) board.step(Map.of(GATE, List.of(at(1, DEF, 1), at(2, DEF, 1))));
        assertEquals(0, gate.points());
        assertEquals(ATT, gate.holderTeamId());
        assertEquals(1, gate.captures().size());
    }

    @Test
    void recaptureOnResetsPointsWithTheNewHolderAndTheOldHolderAttacks() {
        SiegeObjectiveBoard board = board(scenario(true));
        stepUntilCaptured(board, GATE, List.of(at(11, ATT, 1)));
        ObjectiveState gate = board.objective(GATE);
        assertFalse(gate.isCapturedFinal());
        assertEquals(500, gate.points(), "points reset with the new holder");
        assertEquals(ATT, gate.holderTeamId());

        // Now the attackers defend it: alone they can't lower it.
        board.step(Map.of(GATE, List.of(at(11, ATT, 1))));
        assertEquals(500, gate.points());
        // The old holder's alliance attacks it.
        board.step(Map.of(GATE, List.of(at(1, DEF, 1))));
        assertEquals(495, gate.points());
        assertTrue(gate.isContested());

        CaptureEvent back = stepUntilCaptured(board, GATE, List.of(at(1, DEF, 1))).captures().get(0);
        assertEquals(ATT, back.previousHolderTeamId());
        assertEquals(DEF, back.newHolderTeamId());
        assertEquals(2, back.captureNumber());
        assertFalse(back.firstCapture());
        assertEquals(DEF, gate.holderTeamId());
    }

    @Test
    void instantVictoryObjectivesAreFinalEvenWithRecapture() {
        SiegeObjectiveBoard board = board(scenario(true));

        BoardStep step = stepUntilCaptured(board, KEEP, List.of(at(11, ATT, 1)));

        assertTrue(step.instantVictoryCapture().isPresent());
        assertTrue(board.objective(KEEP).isCapturedFinal());
        assertEquals(ATT, board.instantVictoryCapture().orElseThrow().newHolderTeamId());
    }

    @Test
    void sideCaptureReducesEveryUncapturedInstantVictoryObjective() {
        SiegeObjectiveBoard board = board(scenario(false));

        BoardStep step = stepUntilCaptured(board, GATE, List.of(at(11, ATT, 1)));

        // floor(500 × 0.4 / 2 side objectives) = 100, applied after this step's captures.
        assertEquals(Map.of(KEEP, 100), step.sideCaptureReductions());
        assertEquals(400, board.objective(KEEP).points());
        stepUntilCaptured(board, WALL, List.of(at(11, ATT, 1)));
        assertEquals(300, board.objective(KEEP).points());
    }

    @Test
    void sideCapturePressureAppliesOnAnObjectivesFirstCaptureOnly() {
        SiegeObjectiveBoard board = board(scenario(true));
        stepUntilCaptured(board, GATE, List.of(at(11, ATT, 1)));
        assertEquals(400, board.objective(KEEP).points());

        // Retaken by the defenders: no refund.
        BoardStep retaken = stepUntilCaptured(board, GATE, List.of(at(1, DEF, 1)));
        assertTrue(retaken.sideCaptureReductions().isEmpty());
        assertEquals(400, board.objective(KEEP).points());

        // Captured by the attackers again: no second reduction.
        BoardStep again = stepUntilCaptured(board, GATE, List.of(at(11, ATT, 1)));
        assertTrue(again.sideCaptureReductions().isEmpty());
        assertEquals(400, board.objective(KEEP).points());
    }

    @Test
    void sidePressureNeverCapturesByItselfTheNextAttackerDoes() {
        KnkSiegeScenario scenario = SiegeTestData.scenario(1, 5,
                List.of(team(DEF, SiegeTeamRole.DEFENDER, 1, "D"), team(ATT, SiegeTeamRole.ATTACKER, 2, "A")),
                List.of(objective(KEEP, true, DEF, 100), objective(GATE, false, DEF)),
                false);
        SiegeObjectiveBoard board = board(scenario);
        // One side objective: floor(100 × 0.4) = 40 per first capture; bring the keep to 30 first.
        for (int i = 0; i < 14; i++) board.step(Map.of(KEEP, List.of(at(11, ATT, 1))));
        assertEquals(30, board.objective(KEEP).points());

        stepUntilCaptured(board, GATE, List.of(at(12, ATT, 1)));
        assertEquals(0, board.objective(KEEP).points());
        assertFalse(board.objective(KEEP).hasBeenCaptured(), "nobody to credit yet");
        assertTrue(board.instantVictoryCapture().isEmpty());

        BoardStep next = board.step(Map.of(KEEP, List.of(at(13, ATT, 1))));
        assertEquals(player(13), next.instantVictoryCapture().orElseThrow().capturerId());
    }

    @Test
    void theCaptureRewardCountsOncePerParticipantPerObjective() {
        SiegeObjectiveBoard board = board(scenario(true));

        CaptureEvent first = stepUntilCaptured(board, GATE, List.of(at(11, ATT, 1))).captures().get(0);
        CaptureEvent retake = stepUntilCaptured(board, GATE, List.of(at(1, DEF, 1))).captures().get(0);
        CaptureEvent again = stepUntilCaptured(board, GATE, List.of(at(11, ATT, 1))).captures().get(0);
        CaptureEvent otherPlayer = stepUntilCaptured(board, GATE, List.of(at(2, DEF, 1))).captures().get(0);

        assertTrue(first.rewardEligible());
        assertTrue(retake.rewardEligible());
        assertFalse(again.rewardEligible(), "player 11 already captured this objective");
        assertEquals(3, again.captureNumber());
        assertTrue(otherPlayer.rewardEligible());
        assertEquals(4, board.objective(GATE).captures().size());
    }

    @Test
    void heldObjectivesAreSpawnableUnlessContested() {
        SiegeObjectiveBoard board = board(scenario(false));
        assertEquals(List.of(KEEP, GATE, WALL), board.spawnableObjectives(DEF).stream().map(ObjectiveState::objectiveId).toList());

        board.step(Map.of(GATE, List.of(at(11, ATT, 1))));

        assertEquals(List.of(KEEP, WALL), board.spawnableObjectives(DEF).stream().map(ObjectiveState::objectiveId).toList());
        assertTrue(board.spawnableObjectives(ATT).isEmpty());
        assertEquals(1, board.objective(GATE).capturePercent());
    }
}

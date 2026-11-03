package net.knightsandkings.knk.core.survival.end;

import net.knightsandkings.knk.core.survival.ReadinessIssue;
import net.knightsandkings.knk.core.survival.SurvivalSubstate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DESIGN §3a, S17, S19, S26, S37, S41, S46, S47. */
class EndConditionEvaluatorTest {
    private static final SurvivalObjective OUTER = new SurvivalObjective("outer", false, false);
    private static final SurvivalObjective KEEP_FLAGGED = new SurvivalObjective("keep", true, false);

    private static EndConditionSnapshot active(int players, int living, SurvivalObjective... objectives) {
        return new EndConditionSnapshot(SurvivalSubstate.WAVE_ACTIVE, players, living, List.of(objectives));
    }

    private static SurvivalObjective held(SurvivalObjective o) {
        return new SurvivalObjective(o.id(), o.instaLose(), true);
    }

    @Test
    void defaultsAreWipeOnlyAndTheMatchContinuesOtherwise() {
        EndConditionEvaluator evaluator = new EndConditionEvaluator(EndConditionSettings.defaults());
        assertEquals(Optional.empty(), evaluator.evaluate(active(3, 2, OUTER)));
        assertEquals(Optional.empty(), evaluator.evaluate(active(3, 2, held(OUTER))), "unflagged capture never ends");
        assertEquals(Optional.of(SurvivalEndReason.TEAM_WIPE), evaluator.evaluate(active(3, 0, OUTER)));
    }

    @Test
    void deathsInTheIntermissionNeverWipe() {
        EndConditionEvaluator evaluator = new EndConditionEvaluator(EndConditionSettings.defaults());
        EndConditionSnapshot breakDeaths = new EndConditionSnapshot(SurvivalSubstate.INTERMISSION, 2, 0, List.of(OUTER));
        assertEquals(Optional.empty(), evaluator.evaluate(breakDeaths));
        EndConditionSnapshot preparing = new EndConditionSnapshot(SurvivalSubstate.PREPARING, 2, 0, List.of(OUTER));
        assertEquals(Optional.empty(), evaluator.evaluate(preparing));
    }

    @Test
    void noPlayersLeftComesFirstAndLeaversAreNotPlayers() {
        EndConditionEvaluator evaluator = new EndConditionEvaluator(EndConditionSettings.defaults());
        assertEquals(Optional.of(SurvivalEndReason.NO_PLAYERS_LEFT), evaluator.evaluate(active(0, 0, held(KEEP_FLAGGED))));
        // Last living player left, ghosts remain: every remaining player is dead → team wipe.
        assertEquals(Optional.of(SurvivalEndReason.TEAM_WIPE), evaluator.evaluate(active(2, 0, OUTER)));
    }

    @Test
    void instaLoseFlagWorksOnItsOwnWithoutAScenarioSwitch() {
        EndConditionEvaluator evaluator = new EndConditionEvaluator(EndConditionSettings.defaults());
        assertEquals(Optional.of(SurvivalEndReason.INSTA_LOSE_OBJECTIVE_CAPTURED),
            evaluator.evaluate(active(2, 2, OUTER, held(KEEP_FLAGGED))));
        assertEquals(Optional.empty(), evaluator.evaluate(active(2, 2, held(OUTER), KEEP_FLAGGED)));
    }

    @Test
    void allCapturedEndsOnlyWhenEnabledAndLobbyOverrideWins() {
        EndConditionEvaluator off = new EndConditionEvaluator(new EndConditionSettings(false, AllCapturedOverride.INHERIT));
        assertEquals(Optional.empty(), off.evaluate(active(2, 2, held(OUTER), held(new SurvivalObjective("inner", false, false)))));

        EndConditionEvaluator scenarioOn = new EndConditionEvaluator(new EndConditionSettings(true, AllCapturedOverride.INHERIT));
        assertEquals(Optional.of(SurvivalEndReason.ALL_OBJECTIVES_CAPTURED),
            scenarioOn.evaluate(active(2, 2, held(OUTER), held(new SurvivalObjective("inner", false, false)))));
        assertEquals(Optional.empty(), scenarioOn.evaluate(active(2, 2, held(OUTER), new SurvivalObjective("inner", false, false))),
            "with recapture, ownership at each capture decides: one objective retaken = not all held");

        EndConditionEvaluator lobbyOff = new EndConditionEvaluator(new EndConditionSettings(true, AllCapturedOverride.OFF));
        assertEquals(Optional.empty(), lobbyOff.evaluate(active(2, 2, held(OUTER))));
        EndConditionEvaluator lobbyOn = new EndConditionEvaluator(new EndConditionSettings(false, AllCapturedOverride.ON));
        assertEquals(Optional.of(SurvivalEndReason.ALL_OBJECTIVES_CAPTURED), lobbyOn.evaluate(active(2, 2, held(OUTER))));
    }

    @Test
    void sameTickPrecedenceIsWipeThenInstaLoseThenAllCaptured() {
        EndConditionEvaluator evaluator = new EndConditionEvaluator(new EndConditionSettings(true, AllCapturedOverride.INHERIT));
        assertEquals(Optional.of(SurvivalEndReason.TEAM_WIPE),
            evaluator.evaluate(active(2, 0, held(OUTER), held(KEEP_FLAGGED))));
        assertEquals(Optional.of(SurvivalEndReason.INSTA_LOSE_OBJECTIVE_CAPTURED),
            evaluator.evaluate(active(2, 1, held(OUTER), held(KEEP_FLAGGED))));
        assertEquals(Optional.of(SurvivalEndReason.ALL_OBJECTIVES_CAPTURED),
            evaluator.evaluate(active(2, 1, held(OUTER), held(new SurvivalObjective("inner", false, false)))));
    }

    @Test
    void allCapturedWithNoObjectivesNeverEnds() {
        EndConditionEvaluator evaluator = new EndConditionEvaluator(new EndConditionSettings(true, AllCapturedOverride.INHERIT));
        assertEquals(Optional.empty(), evaluator.evaluate(active(1, 1)));
    }

    @Test
    void readinessWarnsWipeOnlyWithoutFailing() {
        EndConditionEvaluator evaluator = new EndConditionEvaluator(EndConditionSettings.defaults());
        List<ReadinessIssue> issues = evaluator.readiness(List.of(OUTER));
        assertEquals(1, issues.size());
        assertEquals("end.wipe-only", issues.get(0).code());
        assertFalse(issues.get(0).isError());
        assertTrue(evaluator.readiness(List.of(KEEP_FLAGGED)).isEmpty(), "flagging an objective is enough");
        EndConditionEvaluator allOn = new EndConditionEvaluator(new EndConditionSettings(true, AllCapturedOverride.INHERIT));
        assertTrue(allOn.readiness(List.of(OUTER)).isEmpty());
        assertEquals("end.all-captured-unreachable", allOn.readiness(List.of()).get(0).code());
    }

    @Test
    void reasonsKnowWhetherTheySettleRewards() {
        assertTrue(SurvivalEndReason.TEAM_WIPE.settlesRewards());
        assertTrue(SurvivalEndReason.INSTA_LOSE_OBJECTIVE_CAPTURED.settlesRewards());
        assertTrue(SurvivalEndReason.ALL_OBJECTIVES_CAPTURED.settlesRewards());
        for (SurvivalEndReason reason : List.of(SurvivalEndReason.NO_PLAYERS_LEFT, SurvivalEndReason.ADMIN_ABORT,
            SurvivalEndReason.SERVER_RESTART, SurvivalEndReason.TECHNICAL_LIMIT)) {
            assertTrue(reason.isAbort(), reason.name());
            assertFalse(reason.settlesRewards(), reason.name());
        }
    }
}

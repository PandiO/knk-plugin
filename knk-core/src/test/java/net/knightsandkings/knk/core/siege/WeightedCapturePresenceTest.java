package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.domain.siege.SiegeTeamRole;
import net.knightsandkings.knk.core.siege.CaptureActivityTracker.Started;
import net.knightsandkings.knk.core.siege.CaptureActivityTracker.Update;
import net.knightsandkings.knk.core.siege.ObjectiveState.CaptureEvent;
import net.knightsandkings.knk.core.siege.ObjectiveState.Presence;
import net.knightsandkings.knk.core.siege.ObjectiveState.StepResult;
import net.knightsandkings.knk.core.siege.SiegeObjectiveBoard.BoardStep;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static net.knightsandkings.knk.core.siege.SiegeTestData.CONFIG;
import static net.knightsandkings.knk.core.siege.SiegeTestData.at;
import static net.knightsandkings.knk.core.siege.SiegeTestData.objective;
import static net.knightsandkings.knk.core.siege.SiegeTestData.player;
import static net.knightsandkings.knk.core.siege.SiegeTestData.team;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Siege Survival link S3 (PandiO/knk-workspace#41, DESIGN §4 "NPC presence must participate in the objective capture
 * calculation"): capture presence is actor-aware and weighted. Weight 1 for every player keeps PvP capture identical
 * (the existing {@link ObjectiveCaptureTest} and {@link CaptureCalculatorTest} run unchanged); an NPC actor presses
 * with its difficulty's weight, can capture, and is credited to nobody.
 */
class WeightedCapturePresenceTest {

    private static final int DEF = 1;
    private static final int ATT = 2;
    private static final int KEEP = 1;
    private static final int GATE = 2;

    private final CaptureCalculator calculator = new CaptureCalculator(CONFIG);

    private static KnkSiegeScenario scenario(boolean allowRecapture) {
        return SiegeTestData.scenario(1, 5,
                List.of(team(DEF, SiegeTeamRole.DEFENDER, 1, "Defenders"), team(ATT, SiegeTeamRole.ATTACKER, 2, "NPCs")),
                List.of(objective(KEEP, true, DEF), objective(GATE, false, DEF)),
                allowRecapture);
    }

    private SiegeObjectiveBoard board(KnkSiegeScenario scenario) {
        return new SiegeObjectiveBoard(scenario, calculator, AllianceResolver.of(scenario));
    }

    private static Presence npc(int n, int teamId, double distance, double weight) {
        return Presence.npc(new UUID(0xABCDL, n), teamId, distance, weight);
    }

    // ===== the calculator: weights sums are the head counts' superset =====

    @ParameterizedTest(name = "{0} actors of weight 1 press like {0} players")
    @ValueSource(ints = {0, 1, 2, 3, 5, 8})
    void wholeNumberWeightsEqualTheHeadCount(int n) {
        for (boolean iv : new boolean[] {false, true}) {
            assertEquals(calculator.attackPressure(n, iv), calculator.attackPressure((double) n, iv));
            assertEquals(calculator.defensePressure(n, iv), calculator.defensePressure((double) n, iv));
            for (int d = 0; d <= 4; d++) {
                assertEquals(calculator.delta(n, d, iv), calculator.delta((double) n, (double) d, iv));
            }
        }
    }

    @Test
    void aFractionalWeightPressesLessThanOnePlayer_andIsTruncatedToWholePoints() {
        // Legacy 5/2: one actor of weight 0.5 presses 5 + (0.5 − 1)·2 = 4 points.
        assertEquals(4.0, calculator.attackPressure(0.5, false));
        assertEquals(4, calculator.delta(0.5, 0, false));
        // 0.25 on an instant-victory objective: 5 + (0.25 − 1)·5 = 1.25 → 1 point.
        assertEquals(1, calculator.delta(0.25, 0, true));
        // Two NPCs of weight 0.5 are one player.
        assertEquals(calculator.delta(1, 0, false), calculator.delta(0.5 + 0.5, 0, false));
        // A very light actor never presses negatively.
        assertTrue(calculator.attackPressure(0.01, true) >= 0);
        assertEquals(0.0, calculator.attackPressure(0, false));
        assertEquals(0.0, calculator.defensePressure(-1, false));
    }

    // ===== the objective step =====

    @Test
    void presenceDefaultsToAPlayerOfWeightOne() {
        Presence p = at(11, ATT, 1.0);
        assertEquals(SiegeActorKind.PLAYER, p.kind());
        assertEquals(1.0, p.weight());
        assertEquals(player(11), p.playerId());
        assertEquals(player(11), p.actorId());
        assertTrue(p.isPlayer());
        assertTrue(p.contributes());

        Presence n = npc(1, ATT, 1.0, 0.5);
        assertEquals(SiegeActorKind.NPC, n.kind());
        assertNull(n.playerId(), "an NPC has no player");
        assertFalse(n.isPlayer());
        assertThrows(IllegalArgumentException.class, () -> npc(2, ATT, 1.0, Double.NaN));
    }

    @Test
    void anNpcPressesWithItsWeight_andAWeightZeroActorIsPresentButHarmless() {
        SiegeObjectiveBoard board = board(scenario(false));
        // One NPC of weight 1 = one player: 5 points per second on a side objective.
        StepResult one = board.step(Map.of(GATE, List.of(npc(1, ATT, 1.0, 1.0)))).steps().get(1);
        assertEquals(495, one.pointsAfter());
        assertEquals(1, one.attackers());
        assertTrue(one.contested());

        // Half a player: 4 points.
        StepResult half = board.step(Map.of(GATE, List.of(npc(1, ATT, 1.0, 0.5)))).steps().get(1);
        assertEquals(491, half.pointsAfter());

        // Weight 0: no pressure, not counted, not contesting.
        StepResult none = board.step(Map.of(GATE, List.of(npc(1, ATT, 1.0, 0.0)))).steps().get(1);
        assertEquals(491, none.pointsAfter());
        assertEquals(0, none.attackers());
        assertFalse(none.contested());
    }

    @Test
    void mixedPlayersAndNpcsSumTheirWeights() {
        SiegeObjectiveBoard board = board(scenario(false));
        // 1 player + 2 NPCs of 0.5 = weight 2 attackers (5 + 2 = 7) vs 1 defender (6): 1 point.
        StepResult step = board.step(Map.of(GATE, List.of(at(11, ATT, 1.0), npc(1, ATT, 2.0, 0.5), npc(2, ATT, 3.0, 0.5),
                at(1, DEF, 1.0)))).steps().get(1);
        assertEquals(499, step.pointsAfter());
        assertEquals(3, step.attackers(), "heads are still counted for feedback");
        assertEquals(1, step.defenders());
        assertEquals(1, step.delta());
    }

    @Test
    void anNpcCaptureCreditsNobody() {
        SiegeObjectiveBoard board = board(scenario(false));
        List<Presence> present = List.of(npc(7, ATT, 0.5, 1.0), at(11, ATT, 3.0));
        BoardStep capture = null;
        for (int i = 0; i < 10_000 && capture == null; i++) {
            BoardStep step = board.step(Map.of(GATE, present));
            if (!step.captures().isEmpty()) capture = step;
        }
        assertTrue(capture != null, "captured");
        CaptureEvent event = capture.captures().get(0);
        assertTrue(event.capturedByNpc());
        assertEquals(SiegeActorKind.NPC, event.capturerKind());
        assertNull(event.capturerId(), "no player to credit");
        assertEquals(new UUID(0xABCDL, 7), event.capturerActorId());
        assertFalse(event.rewardEligible(), "NPC captures earn no capture reward");
        assertEquals(ATT, event.newHolderTeamId());
        assertEquals(DEF, event.previousHolderTeamId());
        assertEquals(ATT, board.objective(GATE).holderTeamId());
    }

    @Test
    void aPlayerCaptureIsUnchangedByNpcsStandingFurtherAway() {
        SiegeObjectiveBoard board = board(scenario(false));
        List<Presence> present = List.of(at(11, ATT, 0.5), npc(7, ATT, 2.0, 1.0));
        BoardStep capture = null;
        for (int i = 0; i < 10_000 && capture == null; i++) {
            BoardStep step = board.step(Map.of(GATE, present));
            if (!step.captures().isEmpty()) capture = step;
        }
        CaptureEvent event = capture.captures().get(0);
        assertFalse(event.capturedByNpc());
        assertEquals(player(11), event.capturerId());
        assertEquals(player(11), event.capturerActorId());
        assertTrue(event.rewardEligible());
    }

    @Test
    void aPvpMatchStepsIdenticallyWithAndWithoutTheActorModel() {
        // Two boards, the same players (weight 1, explicitly and by default) second by second.
        SiegeObjectiveBoard plain = board(scenario(true));
        SiegeObjectiveBoard explicit = board(scenario(true));
        List<Presence> plainPresence = List.of(at(11, ATT, 1.0), at(12, ATT, 2.0), at(1, DEF, 0.5));
        List<Presence> explicitPresence = new ArrayList<>();
        for (Presence p : plainPresence) {
            explicitPresence.add(new Presence(p.actorId(), p.teamId(), p.distance(), SiegeActorKind.PLAYER, 1.0));
        }
        for (int i = 0; i < 1_000; i++) {
            BoardStep a = plain.step(Map.of(GATE, plainPresence, KEEP, plainPresence));
            BoardStep b = explicit.step(Map.of(GATE, explicitPresence, KEEP, explicitPresence));
            assertEquals(a.steps(), b.steps());
            assertEquals(a.captures(), b.captures());
        }
    }

    // ===== capture feedback =====

    @Test
    void feedbackNamesPlayersOnly_butCountsTheNpcTeam() {
        KnkSiegeScenario scenario = scenario(false);
        AllianceResolver alliances = AllianceResolver.of(scenario);
        SiegeObjectiveBoard board = board(scenario);
        CaptureActivityTracker tracker = new CaptureActivityTracker();
        Map<Integer, List<Presence>> presence = Map.of(GATE, List.of(npc(1, ATT, 0.5, 1.0), at(11, ATT, 2.0)));

        Update update = tracker.step(board.step(presence).steps(), presence, board::objective, alliances);
        Started started = update.started().get(0);
        assertEquals(List.of(player(11)), started.actors(), "the NPC has no player to name");
        assertEquals(List.of(ATT), started.actorTeamIds());
        assertEquals(List.of(player(11)), update.ongoing().get(0).actors());

        Map<Integer, List<Presence>> npcOnly = Map.of(GATE, List.of(npc(1, ATT, 0.5, 1.0)));
        Update second = tracker.step(board.step(npcOnly).steps(), npcOnly, board::objective, alliances);
        assertEquals(List.of(), second.ongoing().get(0).actors());
        assertEquals(CaptureActivityTracker.Activity.ATTACKING, second.ongoing().get(0).activity());
    }
}

package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.domain.siege.SiegeTeamRole;
import net.knightsandkings.knk.core.siege.SiegeObjectiveLabels.Label;
import net.knightsandkings.knk.core.siege.SiegeObjectiveLabels.Relation;
import net.knightsandkings.knk.core.siege.SiegeObjectiveLabels.Status;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static net.knightsandkings.knk.core.siege.SiegeTestData.CONFIG;
import static net.knightsandkings.knk.core.siege.SiegeTestData.at;
import static net.knightsandkings.knk.core.siege.SiegeTestData.objective;
import static net.knightsandkings.knk.core.siege.SiegeTestData.team;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Siege Phase 5 follow-up: the holding side sees "ours", attackers "enemy", non-members a neutral label. */
class SiegeObjectiveLabelsTest {

    private static final int DEF = 1;
    private static final int ATT = 2;

    private final KnkSiegeScenario scenario = SiegeTestData.scenario(1, 5,
            List.of(team(DEF, SiegeTeamRole.DEFENDER, 1, "D"), team(ATT, SiegeTeamRole.ATTACKER, 2, "A")),
            List.of(objective(1, true, DEF), objective(2, false, DEF)), false);
    private final AllianceResolver alliances = AllianceResolver.of(scenario);
    private final SiegeObjectiveBoard board = new SiegeObjectiveBoard(scenario, new CaptureCalculator(CONFIG), alliances);

    @Test
    void relationDependsOnTheViewersAlliance() {
        ObjectiveState gate = board.objective(2);
        assertEquals(new Label(Relation.OURS, Status.SECURE, 0), SiegeObjectiveLabels.forViewer(gate, 1, alliances));
        assertEquals(new Label(Relation.ENEMY, Status.SECURE, 0), SiegeObjectiveLabels.forViewer(gate, 2, alliances));
        assertEquals(Relation.NEUTRAL, SiegeObjectiveLabels.forViewer(gate, null, alliances).relation());
    }

    @Test
    void statusFollowsTheCaptureState() {
        board.step(Map.of(2, List.of(at(11, ATT, 1.0))));
        ObjectiveState gate = board.objective(2);
        Label underAttack = SiegeObjectiveLabels.forViewer(gate, 1, alliances);
        assertEquals(Status.UNDER_ATTACK, underAttack.status());
        assertEquals(1, underAttack.capturePercent());

        board.step(Map.of());
        assertEquals(Status.WEAKENED, SiegeObjectiveLabels.forViewer(gate, 1, alliances).status());

        for (int i = 0; i < 200 && !gate.isCapturedFinal(); i++) board.step(Map.of(2, List.of(at(11, ATT, 1.0))));
        assertEquals(new Label(Relation.OURS, Status.FINAL, 100), SiegeObjectiveLabels.forViewer(gate, 2, alliances),
                "after the capture the attackers hold it: theirs now");
        assertEquals(Relation.ENEMY, SiegeObjectiveLabels.forViewer(gate, 1, alliances).relation());
    }
}

package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.domain.siege.SiegeTeamRole;
import net.knightsandkings.knk.core.siege.ObjectiveState.Presence;
import net.knightsandkings.knk.core.siege.SiegeObjectiveBoard.BoardStep;
import net.knightsandkings.knk.core.siege.economy.SiegePointsEarnCalculator;
import net.knightsandkings.knk.core.siege.economy.SiegePointsEarnCalculator.Award;
import net.knightsandkings.knk.core.siege.economy.SiegePointsSettings;
import net.knightsandkings.knk.core.siege.economy.SiegePointsSource;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static net.knightsandkings.knk.core.siege.SiegeTestData.CONFIG;
import static net.knightsandkings.knk.core.siege.SiegeTestData.at;
import static net.knightsandkings.knk.core.siege.SiegeTestData.objective;
import static net.knightsandkings.knk.core.siege.SiegeTestData.player;
import static net.knightsandkings.knk.core.siege.SiegeTestData.scenario;
import static net.knightsandkings.knk.core.siege.SiegeTestData.team;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A08 capture progress and captures (siege shop P16, W50; PandiO/knk-workspace#866) on a real objective board:
 * 3 points per percent moved towards a side to each of that side's members in the radius, both directions, and
 * 100 to the capturing side's members inside at the capture. Legacy capture rates: one attacker moves 5 points a
 * second, two move 7, one defender 6; the objective has 100 capture points, so a point is a percent.
 */
class SiegePointsCaptureTest {

    private static final int DEFENDERS = 1;
    private static final int ATTACKERS = 2;
    private static final int OBJECTIVE = 2;

    private final KnkSiegeScenario scenario = scenario(1, 1,
            List.of(team(DEFENDERS, SiegeTeamRole.DEFENDER, 1, "Defenders"), team(ATTACKERS, SiegeTeamRole.ATTACKER, 2, "Attackers")),
            List.of(objective(OBJECTIVE, false, DEFENDERS, 100)), true);
    private final AllianceResolver alliances = AllianceResolver.of(scenario);
    private final SiegeObjectiveBoard board = new SiegeObjectiveBoard(scenario, new CaptureCalculator(CONFIG), alliances);
    private final SiegePointsEarnCalculator calculator = new SiegePointsEarnCalculator(SiegePointsSettings.defaults());
    private final Map<UUID, Long> progress = new HashMap<>();
    private final Map<UUID, Long> captures = new HashMap<>();

    private static final UUID A1 = player(1);
    private static final UUID A2 = player(2);
    private static final UUID D1 = player(11);

    private void step(Presence... present) {
        Map<Integer, List<Presence>> presence = present.length == 0 ? Map.of() : Map.of(OBJECTIVE, List.of(present));
        BoardStep step = board.step(presence);
        for (Award a : calculator.boardStep(step, presence, board::objective, alliances)) {
            (a.source() == SiegePointsSource.CAPTURE ? captures : progress).merge(a.player(), a.amount(), Long::sum);
            assertTrue(a.source() == SiegePointsSource.CAPTURE || a.source() == SiegePointsSource.CAPTURE_PROGRESS);
        }
    }

    @Test
    void attackersEarnPerPercentTheyMoveAndDefendersEarnForPushingBack() {
        step(at(1, ATTACKERS, 1), at(2, ATTACKERS, 2));          // 7 % towards the attackers
        assertEquals(21L, progress.get(A1));
        assertEquals(21L, progress.get(A2));
        assertEquals(null, progress.get(D1), "D1 is outside the radius");

        step(at(11, DEFENDERS, 1));                               // pushed back 6 %
        assertEquals(18L, progress.get(D1));
        assertEquals(21L, progress.get(A1));
    }

    @Test
    void nobodyEarnsWhileTheObjectiveDoesNotMove() {
        step(at(11, DEFENDERS, 1));                               // already full: 0 %
        step(at(1, ATTACKERS, 1), at(11, DEFENDERS, 1));          // 5 vs 6: pushed against a full objective
        assertEquals(Map.of(), progress);
    }

    @Test
    void aCapturePays100ToTheCapturingSideInsideAndTheLastPercentsCount() {
        step(at(1, ATTACKERS, 1), at(2, ATTACKERS, 2));           // 100 -> 93
        for (int i = 0; i < 18; i++) step(at(1, ATTACKERS, 1));    // 93 -> 3
        step(at(1, ATTACKERS, 1), at(11, DEFENDERS, 1));           // 5 vs 6: back to 4, D1 earns 3
        step(at(1, ATTACKERS, 1));                                 // 4 -> 0: captured; A2 is outside
        assertEquals(1, board.objective(OBJECTIVE).captures().size());
        assertEquals(Map.of(A1, 100L), captures);
        assertEquals(21L + 18 * 15 + 12, progress.get(A1));
        assertEquals(21L, progress.get(A2));
        assertEquals(3L, progress.get(D1));
    }
}

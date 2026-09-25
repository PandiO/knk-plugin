package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeObjective;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeTeam;
import net.knightsandkings.knk.core.domain.siege.SiegeEndReason;
import net.knightsandkings.knk.core.domain.siege.SiegeTeamRole;
import net.knightsandkings.knk.core.siege.ObjectiveState.Presence;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static net.knightsandkings.knk.core.siege.SiegeTestData.CONFIG;
import static net.knightsandkings.knk.core.siege.SiegeTestData.at;
import static net.knightsandkings.knk.core.siege.SiegeTestData.objective;
import static net.knightsandkings.knk.core.siege.SiegeTestData.team;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Siege Phase 4: N-team, holder-relative scoring (DESIGN §7.1). v2 decided who defends by
 * {@code teamName.equalsIgnoreCase("cinixians")}; renaming every team must now change nothing.
 */
class HolderRelativeScoringTest {

    private final CaptureCalculator calculator = new CaptureCalculator(CONFIG);

    /** Defender team 1 (alliance 1) and its ally team 3; two rival attacker teams 2 and 4 (alliances 2, 3). */
    private static List<KnkSiegeTeam> teams(String... names) {
        return List.of(
                team(1, SiegeTeamRole.DEFENDER, 1, names[0]),
                team(2, SiegeTeamRole.ATTACKER, 2, names[1]),
                team(3, SiegeTeamRole.ATTACKER, 1, names[2]),
                team(4, SiegeTeamRole.ATTACKER, 3, names[3]));
    }

    private static List<KnkSiegeObjective> objectives() {
        return List.of(
                objective(1, true, 1),   // the keep, held by the defenders
                objective(2, false, 1),  // a gate, held by the defenders
                objective(3, false, 2)); // an outpost the attackers of alliance 2 start with
    }

    /** A fixed script of who stands where, for 400 seconds. */
    private static List<Map<Integer, List<Presence>>> script() {
        List<Map<Integer, List<Presence>>> seconds = new ArrayList<>();
        for (int s = 0; s < 400; s++) {
            List<Presence> gate = s < 150
                    ? List.of(at(21, 2, 1), at(41, 4, 1.5), at(11, 1, 2)) // two rival attacker teams vs one defender
                    : List.of(at(21, 2, 1));
            List<Presence> outpost = List.of(at(31, 3, 1)); // the defenders' ally attacks team 2's outpost
            List<Presence> keep = s > 300 ? List.of(at(42, 4, 1), at(12, 1, 1)) : List.of();
            seconds.add(Map.of(1, keep, 2, gate, 3, outpost));
        }
        return seconds;
    }

    private record Outcome(List<String> steps, WinResolver.Result result) {}

    private Outcome play(List<KnkSiegeTeam> teams) {
        KnkSiegeScenario scenario = SiegeTestData.scenario(1, 5, teams, objectives(), true);
        AllianceResolver alliances = AllianceResolver.of(scenario);
        SiegeObjectiveBoard board = new SiegeObjectiveBoard(scenario, calculator, alliances);
        List<String> log = new ArrayList<>();
        for (Map<Integer, List<Presence>> second : script()) {
            board.step(second).steps().forEach(r -> log.add(r.objectiveId() + ":" + r.pointsAfter() + ":" + r.delta()));
            board.objectives().forEach(o -> log.add(o.objectiveId() + "@" + o.holderTeamId()));
        }
        WinResolver.Result result = new WinResolver(alliances).resolve(SiegeEndReason.TIME_EXPIRED, board, Set.of(1, 2, 3));
        return new Outcome(log, result);
    }

    @Test
    void renamingEveryTeamChangesNothing() {
        Outcome original = play(teams("Cinixians", "Raiders", "Garrison", "Mercenaries"));
        // Swap the names around, including handing "cinixians" to an attacker team.
        Outcome renamed = play(teams("Raiders", "cinixians", "Mercenaries", "CINIXIANS"));
        Outcome blank = play(teams("", "", "", ""));

        // The script really moves things: the gate falls to team 2, the outpost to team 3.
        assertTrue(original.steps().contains("2@2"));
        assertTrue(original.steps().contains("3@3"));
        assertEquals(1, original.result().winnerOrNull());

        assertEquals(original, renamed);
        assertEquals(original, blank);
    }

    @Test
    void defendersAreTheHoldersAllianceWhateverTheirRole() {
        KnkSiegeScenario scenario = SiegeTestData.scenario(1, 5, teams("a", "b", "c", "d"), objectives(), false);
        AllianceResolver alliances = AllianceResolver.of(scenario);
        SiegeObjectiveBoard board = new SiegeObjectiveBoard(scenario, calculator, alliances);

        ObjectiveState.StepResult outpost = board.step(Map.of(3, List.of(at(31, 3, 1), at(11, 1, 1), at(22, 2, 1)))).steps().get(2);

        // Objective 3 is held by attacker-role team 2: its own member defends, while the
        // Defender-role team 1 and its ally team 3 attack it.
        assertEquals(2, outpost.attackers());
        assertEquals(1, outpost.defenders());
        assertEquals(1, outpost.delta());
    }

    @Test
    void rivalAttackerAlliancesBothCountAsAttackers() {
        KnkSiegeScenario scenario = SiegeTestData.scenario(1, 5, teams("a", "b", "c", "d"), objectives(), false);
        SiegeObjectiveBoard board = new SiegeObjectiveBoard(scenario, calculator, AllianceResolver.of(scenario));

        ObjectiveState.StepResult gate = board.step(Map.of(2, List.of(at(21, 2, 1), at(41, 4, 1)))).steps().get(1);

        assertEquals(2, gate.attackers());
        assertEquals(0, gate.defenders());
        assertEquals(7, gate.delta());
        assertTrue(gate.contested());
    }

    @Test
    void alliesDefendTogether() {
        KnkSiegeScenario scenario = SiegeTestData.scenario(1, 5, teams("a", "b", "c", "d"), objectives(), false);
        AllianceResolver alliances = AllianceResolver.of(scenario);

        assertTrue(alliances.areAllies(1, 3));
        assertTrue(alliances.areEnemies(2, 4));
        assertEquals(Set.of(1, 2, 3), alliances.alliances());
        assertEquals(List.of(1, 3), alliances.teamsOf(1));
        assertTrue(alliances.hasDefender(1));
        assertEquals(Set.of(1, 3), alliances.alliancesWithMembers(Map.of(1, 0, 3, 2, 4, 1, 99, 5)));

        SiegeObjectiveBoard board = new SiegeObjectiveBoard(scenario, calculator, alliances);
        ObjectiveState.StepResult gate = board.step(Map.of(2, List.of(at(11, 1, 1), at(31, 3, 1), at(21, 2, 1)))).steps().get(1);
        assertEquals(1, gate.attackers());
        assertEquals(2, gate.defenders());
    }
}

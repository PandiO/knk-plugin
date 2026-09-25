package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeObjective;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeTeam;
import net.knightsandkings.knk.core.domain.siege.SiegeEndReason;
import net.knightsandkings.knk.core.domain.siege.SiegeTeamRole;
import net.knightsandkings.knk.core.siege.ObjectiveState.Presence;
import net.knightsandkings.knk.core.siege.WinResolver.Decision;
import net.knightsandkings.knk.core.siege.WinResolver.Result;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static net.knightsandkings.knk.core.siege.SiegeTestData.CONFIG;
import static net.knightsandkings.knk.core.siege.SiegeTestData.at;
import static net.knightsandkings.knk.core.siege.SiegeTestData.objective;
import static net.knightsandkings.knk.core.siege.SiegeTestData.team;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Siege Phase 4: the win matrix (DESIGN §7.5). */
class WinResolverTest {

    private static final Set<Integer> EVERYONE = Set.of(1, 2, 3);

    private final CaptureCalculator calculator = new CaptureCalculator(CONFIG);

    private static final List<KnkSiegeTeam> TWO_SIDES = List.of(
            team(1, SiegeTeamRole.DEFENDER, 1, "Defenders"),
            team(2, SiegeTeamRole.ATTACKER, 2, "Attackers"));

    private static final List<KnkSiegeTeam> THREE_SIDES = List.of(
            team(1, SiegeTeamRole.DEFENDER, 1, "Defenders"),
            team(2, SiegeTeamRole.ATTACKER, 2, "Red"),
            team(3, SiegeTeamRole.ATTACKER, 3, "Blue"));

    private record Match(SiegeObjectiveBoard board, WinResolver resolver) {
        Result resolve(SiegeEndReason reason) {
            return resolver.resolve(reason, board, EVERYONE);
        }
    }

    private Match match(List<KnkSiegeTeam> teams, List<KnkSiegeObjective> objectives) {
        KnkSiegeScenario scenario = SiegeTestData.scenario(1, 5, teams, objectives, false);
        AllianceResolver alliances = AllianceResolver.of(scenario);
        return new Match(new SiegeObjectiveBoard(scenario, calculator, alliances), new WinResolver(alliances));
    }

    private static void capture(SiegeObjectiveBoard board, int objectiveId, Presence attacker) {
        for (int i = 0; i < 1000 && !board.objective(objectiveId).hasBeenCaptured(); i++) {
            board.step(Map.of(objectiveId, List.of(attacker)));
        }
        assertTrue(board.objective(objectiveId).hasBeenCaptured());
    }

    @Test
    void instantVictoryCaptureWinsForTheCapturersAlliance() {
        Match m = match(THREE_SIDES, List.of(objective(1, true, 1), objective(2, false, 1)));
        capture(m.board(), 1, at(31, 3, 1));

        Result result = m.resolve(SiegeEndReason.INSTANT_VICTORY);

        assertEquals(OptionalInt.of(3), result.winningAllianceGroup());
        assertEquals(Decision.INSTANT_VICTORY_CAPTURE, result.decision());
    }

    @Test
    void timeoutWithASingleInstantVictoryHolderGoesToThatAlliance() {
        Match m = match(TWO_SIDES, List.of(objective(1, true, 1), objective(2, false, 1), objective(3, false, 1)));
        // The attackers take both side objectives, but never the keep.
        capture(m.board(), 2, at(21, 2, 1));
        capture(m.board(), 3, at(21, 2, 1));

        Result result = m.resolve(SiegeEndReason.TIME_EXPIRED);

        assertEquals(OptionalInt.of(1), result.winningAllianceGroup(), "defenders win on time, as in v1/v2");
        assertEquals(Decision.INSTANT_VICTORY_HOLDER, result.decision());
    }

    @Test
    void timeoutWithMixedInstantVictoryHoldersGoesToTheHolderOfMostObjectives() {
        Match m = match(TWO_SIDES, List.of(
                objective(1, true, 1), objective(2, true, 2), objective(3, false, 1), objective(4, false, 1)));
        capture(m.board(), 3, at(21, 2, 1));
        capture(m.board(), 4, at(21, 2, 1));

        Result result = m.resolve(SiegeEndReason.TIME_EXPIRED);

        assertEquals(OptionalInt.of(2), result.winningAllianceGroup());
        assertEquals(Decision.MOST_OBJECTIVES, result.decision());
    }

    @Test
    void mixedInstantVictoryHoldersTiedGoToTheDefenderAlliance() {
        Match m = match(TWO_SIDES, List.of(objective(1, true, 1), objective(2, true, 2)));

        Result result = m.resolve(SiegeEndReason.TIME_EXPIRED);

        assertEquals(OptionalInt.of(1), result.winningAllianceGroup());
        assertEquals(Decision.DEFENDER_ALLIANCE, result.decision());
    }

    @Test
    void mixedInstantVictoryHoldersTiedWithoutADefenderAreADraw() {
        Match m = match(THREE_SIDES, List.of(objective(1, true, 2), objective(2, true, 3), objective(3, false, 1)));

        Result result = m.resolve(SiegeEndReason.TIME_EXPIRED);

        // Alliance 1 holds a side objective but no instant-victory objective, so it isn't a contender.
        assertTrue(result.isDraw());
        assertEquals(OptionalInt.empty(), result.winningAllianceGroup());
        assertNull(result.winnerOrNull());
    }

    @Test
    void tiedAlliancesThatBothContainADefenderAreADraw() {
        List<KnkSiegeTeam> twoDefenders = List.of(
                team(1, SiegeTeamRole.DEFENDER, 1, "North"), team(2, SiegeTeamRole.DEFENDER, 2, "South"));
        Match m = match(twoDefenders, List.of(objective(1, true, 1), objective(2, true, 2)));

        assertTrue(m.resolve(SiegeEndReason.TIME_EXPIRED).isDraw());
    }

    @Test
    void noInstantVictoryObjectiveMostObjectivesWins() {
        Match m = match(TWO_SIDES, List.of(objective(1, false, 1), objective(2, false, 1), objective(3, false, 1)));
        capture(m.board(), 1, at(21, 2, 1));
        capture(m.board(), 2, at(21, 2, 1));

        Result result = m.resolve(SiegeEndReason.TIME_EXPIRED);

        assertEquals(OptionalInt.of(2), result.winningAllianceGroup());
        assertEquals(Decision.MOST_OBJECTIVES, result.decision());
    }

    @Test
    void noInstantVictoryObjectiveTieGoesToTheDefenderAlliance() {
        Match m = match(TWO_SIDES, List.of(objective(1, false, 1), objective(2, false, 1)));
        capture(m.board(), 1, at(21, 2, 1));

        Result result = m.resolve(SiegeEndReason.TIME_EXPIRED);

        assertEquals(OptionalInt.of(1), result.winningAllianceGroup());
        assertEquals(Decision.DEFENDER_ALLIANCE, result.decision());
    }

    @Test
    void noInstantVictoryObjectiveTieBetweenAttackersIsADraw() {
        Match m = match(THREE_SIDES, List.of(objective(1, false, 2), objective(2, false, 3)));

        assertTrue(m.resolve(SiegeEndReason.TIME_EXPIRED).isDraw());
    }

    @Test
    void notEnoughPlayersUsesTheNormalWinRules() {
        Match m = match(TWO_SIDES, List.of(objective(1, true, 1), objective(2, false, 1)));

        Result result = m.resolve(SiegeEndReason.NOT_ENOUGH_PLAYERS);

        assertEquals(OptionalInt.of(1), result.winningAllianceGroup());
        assertEquals(SiegeEndReason.NOT_ENOUGH_PLAYERS, result.reason());
    }

    @Test
    void eliminationLeavesTheLastAllianceStanding() {
        Match m = match(THREE_SIDES, List.of(objective(1, true, 1)));

        Result result = m.resolver().resolve(SiegeEndReason.TEAM_ELIMINATED, m.board(), Set.of(3));
        Result nobody = m.resolver().resolve(SiegeEndReason.TEAM_ELIMINATED, m.board(), Set.of());

        assertEquals(OptionalInt.of(3), result.winningAllianceGroup());
        assertEquals(Decision.LAST_ALLIANCE_STANDING, result.decision());
        assertTrue(nobody.isDraw());
    }

    @Test
    void adminStopAndServerRestartAbortWithoutAWinner() {
        Match m = match(TWO_SIDES, List.of(objective(1, true, 1)));
        capture(m.board(), 1, at(21, 2, 1));

        for (SiegeEndReason reason : List.of(SiegeEndReason.ADMIN_STOPPED, SiegeEndReason.SERVER_RESTART)) {
            Result result = m.resolve(reason);
            assertTrue(result.isAborted());
            assertEquals(OptionalInt.empty(), result.winningAllianceGroup());
        }
    }

    @Test
    void membershipChecksForEliminationThenPlayerCount() {
        Match m = match(THREE_SIDES, List.of(objective(1, true, 1)));
        WinResolver resolver = m.resolver();

        assertEquals(Optional.of(SiegeEndReason.TEAM_ELIMINATED), resolver.membershipEnd(Map.of(1, 3, 2, 0, 3, 0), 2));
        assertEquals(Optional.of(SiegeEndReason.NOT_ENOUGH_PLAYERS), resolver.membershipEnd(Map.of(1, 1, 2, 1, 3, 0), 3));
        assertEquals(Optional.empty(), resolver.membershipEnd(Map.of(1, 1, 2, 1, 3, 0), 2));
    }
}

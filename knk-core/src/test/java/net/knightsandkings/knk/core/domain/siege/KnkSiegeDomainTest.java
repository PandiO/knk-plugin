package net.knightsandkings.knk.core.domain.siege;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Siege Phase 4: domain records keep the payload's order rules and parse the API's string enums. */
class KnkSiegeDomainTest {

    @Test
    void parsesApiEnumNamesInAnyCase() {
        assertEquals(SiegeTeamRole.DEFENDER, SiegeTeamRole.fromApi("Defender"));
        assertEquals(SiegeTeamRole.ATTACKER, SiegeTeamRole.fromApi("attacker"));
        assertEquals(SiegeLobbyMode.CONTINUOUS, SiegeLobbyMode.fromApi("Continuous"));
        assertEquals(SiegeNonMemberGateView.PASS_THROUGH_ONLY, SiegeNonMemberGateView.fromApi("PassThroughOnly"));
        assertEquals(SiegeNonMemberGateView.PRE_LOCKDOWN_VIEW, SiegeNonMemberGateView.fromApi("PRE_LOCKDOWN_VIEW"));
        assertEquals(SiegeGateState.CLOSED, SiegeGateState.fromApi("CLOSED", SiegeGateState.OPEN));
    }

    @Test
    void unknownEnumValuesFallBackSafely() {
        assertEquals(SiegeTeamRole.ATTACKER, SiegeTeamRole.fromApi(null));
        // A lobby mode this plugin doesn't know must never be auto-run as Continuous.
        assertEquals(SiegeLobbyMode.SCHEDULED, SiegeLobbyMode.fromApi("Tournament"));
        assertEquals(SiegeGateState.OPEN, SiegeGateState.fromApi("OPENING", SiegeGateState.OPEN));
    }

    @Test
    void endReasonApiNamesArePascalCase() {
        assertEquals("InstantVictory", SiegeEndReason.INSTANT_VICTORY.apiName());
        assertEquals("NotEnoughPlayers", SiegeEndReason.NOT_ENOUGH_PLAYERS.apiName());
        assertTrue(SiegeEndReason.SERVER_RESTART.isAbort());
        assertFalse(SiegeEndReason.TIME_EXPIRED.isAbort());
    }

    @Test
    void scenarioOrdersTeamsSpawnpointsAndObjectivesBySortOrderThenId() {
        KnkSiegeTeam late = new KnkSiegeTeam(9, 1, SiegeTeamRole.ATTACKER, 2, null, "B", "RED", null, null, List.of());
        KnkSiegeTeam early = new KnkSiegeTeam(4, 0, SiegeTeamRole.DEFENDER, 1, null, "A", "BLUE", null, null, List.of(
                new KnkSiegeSpawnpoint(3, 1, "second", null, 4),
                new KnkSiegeSpawnpoint(8, 0, "default", null, 4)));
        KnkSiegeObjective o2 = objective(2, 0);
        KnkSiegeObjective o1 = objective(1, 0);
        KnkSiegeObjective o0 = objective(7, -1);

        KnkSiegeScenario scenario = new KnkSiegeScenario(1, "S", null, 5, "Town", null, null, null, 2, 20,
                null, null, null, null, true, false, true, List.of(late, early), List.of(o2, o1, o0), null);

        assertEquals(List.of(4, 9), scenario.teams().stream().map(KnkSiegeTeam::id).toList());
        assertEquals(List.of(7, 1, 2), scenario.objectives().stream().map(KnkSiegeObjective::id).toList());
        assertEquals("default", scenario.teams().get(0).defaultSpawnpoint().orElseThrow().name());
        assertEquals(4, scenario.firstDefender().orElseThrow().id());
        assertEquals(KnkSiegeMatchLength.DEFAULT, scenario.matchLength());
    }

    @Test
    void runtimeConfigFindsLobbiesByKeyCaseInsensitively() {
        KnkSiegeLobby lobby = new KnkSiegeLobby(1, "Cinix", "test-cinix", SiegeLobbyMode.CONTINUOUS,
                300, 900, 2, true, null, null);
        KnkSiegeRuntimeConfig config = new KnkSiegeRuntimeConfig(null, KnkSiegeConfiguration.legacyDefaults(), List.of(lobby));

        assertEquals(1, config.lobbyByKey(" Test-Cinix ").orElseThrow().id());
        assertTrue(config.lobbyByKey("other").isEmpty());
        assertFalse(lobby.hasReadyScenario());
    }

    private static KnkSiegeObjective objective(int id, int sortOrder) {
        return new KnkSiegeObjective(id, sortOrder, "O" + id, null, null, 500, 2.5, false, 4, true, null);
    }
}

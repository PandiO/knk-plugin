package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeConfiguration;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeGate;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeLobby;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchLength;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeObjective;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeRotationEntry;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeTeam;
import net.knightsandkings.knk.core.domain.siege.SiegeGateState;
import net.knightsandkings.knk.core.domain.siege.SiegeLobbyMode;
import net.knightsandkings.knk.core.domain.siege.SiegeTeamRole;
import net.knightsandkings.knk.core.siege.ObjectiveState.Presence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Fixture builders for the Bukkit-free siege tests. */
final class SiegeTestData {
    private SiegeTestData() {}

    static final KnkSiegeConfiguration CONFIG = KnkSiegeConfiguration.legacyDefaults();

    static UUID player(int n) {
        return new UUID(0L, n);
    }

    static KnkSiegeTeam team(int id, SiegeTeamRole role, int allianceGroup, String name) {
        return new KnkSiegeTeam(id, id, role, allianceGroup, null, name, "WHITE", null, null, List.of());
    }

    static KnkSiegeObjective objective(int id, boolean instantVictory, int holderTeamId) {
        return objective(id, instantVictory, holderTeamId, 500);
    }

    static KnkSiegeObjective objective(int id, boolean instantVictory, int holderTeamId, int capturePoints) {
        return new KnkSiegeObjective(id, id, "Objective " + id, null, null, capturePoints, 2.5,
                instantVictory, holderTeamId, true, SiegeGateState.OPEN);
    }

    static KnkSiegeScenario scenario(int id, int townId, List<KnkSiegeTeam> teams, List<KnkSiegeObjective> objectives,
                                     boolean allowRecapture) {
        return scenario(id, townId, 2, teams, objectives, allowRecapture, List.of());
    }

    static KnkSiegeScenario scenario(int id, int townId, int playersMin, List<KnkSiegeTeam> teams,
                                     List<KnkSiegeObjective> objectives, boolean allowRecapture, List<KnkSiegeGate> gates) {
        return new KnkSiegeScenario(id, "Scenario " + id, null, townId, "Town " + townId, null, List.of(), null,
                playersMin, 20, null, null, KnkSiegeMatchLength.DEFAULT, null, true, allowRecapture, true,
                teams, objectives, gates);
    }

    /** Defenders (team 1, alliance 1) vs attackers (team 2, alliance 2); an IV keep and a side gate. */
    static KnkSiegeScenario twoTeamScenario(int id, int townId) {
        return scenario(id, townId,
                List.of(team(1, SiegeTeamRole.DEFENDER, 1, "Cinixians"), team(2, SiegeTeamRole.ATTACKER, 2, "Raiders")),
                List.of(objective(1, true, 1), objective(2, false, 1)),
                false);
    }

    static KnkSiegeLobby lobby(int id, int matchmakingSeconds, int cooldownSeconds, int voteCandidateCount,
                               boolean allowRandomVote, KnkSiegeScenario... scenarios) {
        List<KnkSiegeRotationEntry> rotation = new ArrayList<>();
        Arrays.stream(scenarios).forEach(s -> rotation.add(new KnkSiegeRotationEntry(1, s)));
        return new KnkSiegeLobby(id, "Lobby " + id, "lobby-" + id, SiegeLobbyMode.CONTINUOUS,
                matchmakingSeconds, cooldownSeconds, voteCandidateCount, allowRandomVote, rotation, List.of());
    }

    static KnkSiegeRotationEntry entry(KnkSiegeScenario scenario) {
        return new KnkSiegeRotationEntry(1, scenario);
    }

    static Presence at(int playerNumber, int teamId, double distance) {
        return new Presence(player(playerNumber), teamId, distance);
    }
}

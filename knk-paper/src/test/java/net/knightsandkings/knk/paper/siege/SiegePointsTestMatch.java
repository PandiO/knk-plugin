package net.knightsandkings.knk.paper.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeConfiguration;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchLength;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeObjective;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeTeam;
import net.knightsandkings.knk.core.domain.siege.SiegeGateState;
import net.knightsandkings.knk.core.domain.siege.SiegeTeamRole;
import net.knightsandkings.knk.core.siege.SiegeLobbyStateMachine;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A small PvP match for the siege points tests (siege shop L1, PandiO/knk-workspace#866): defenders (team 1, alliance 1)
 * D1 against attackers (team 2, alliance 2) A1 and A2, one side objective with 100 capture points, and mocked players
 * that remember the board and the location they were given. The {@link World} is held here (a {@code Location} keeps
 * it only weakly).
 */
final class SiegePointsTestMatch {

    static final int DEFENDERS = 1;
    static final int ATTACKERS = 2;
    static final UUID A1 = new UUID(0, 1);
    static final UUID A2 = new UUID(0, 2);
    static final UUID D1 = new UUID(0, 11);

    final World world = mock(World.class);
    final Map<UUID, Player> players = new HashMap<>();
    final Map<UUID, AtomicReference<Scoreboard>> boards = new HashMap<>();
    final Map<UUID, AtomicReference<Location>> locations = new HashMap<>();
    final SiegeMatch match;
    final SiegeLobbyRuntime lobby = mock(SiegeLobbyRuntime.class);

    SiegePointsTestMatch(KnkSiegeConfiguration configuration) {
        KnkSiegeTeam defenders = new KnkSiegeTeam(DEFENDERS, 0, SiegeTeamRole.DEFENDER, 1, null, "Defenders", "BLUE",
                null, null, List.of());
        KnkSiegeTeam attackers = new KnkSiegeTeam(ATTACKERS, 1, SiegeTeamRole.ATTACKER, 2, null, "Attackers", "RED",
                null, null, List.of());
        KnkSiegeObjective keep = new KnkSiegeObjective(2, 2, "Keep", null, null, 100, 2.5, false, DEFENDERS, true,
                SiegeGateState.OPEN);
        KnkSiegeScenario scenario = new KnkSiegeScenario(1, "Scenario", null, 1, "Town", null, List.of(), null,
                2, 20, null, null, KnkSiegeMatchLength.DEFAULT, null, true, true, true,
                List.of(defenders, attackers), List.of(keep), List.of(), List.of());
        match = new SiegeMatch(scenario, configuration, "token-1",
                Map.of(DEFENDERS, List.of(D1), ATTACKERS, List.of(A1, A2)));
        SiegeLobbyStateMachine machine = mock(SiegeLobbyStateMachine.class);
        when(machine.secondsRemaining()).thenReturn(600);
        when(lobby.machine()).thenReturn(machine);
        when(lobby.displayName()).thenReturn("Siege");
        when(lobby.id()).thenReturn(7);
        when(lobby.match()).thenReturn(java.util.Optional.of(match));
        player(A1, "Alice");
        player(A2, "Anna");
        player(D1, "Dave");
    }

    private void player(UUID id, String name) {
        Player p = mock(Player.class);
        AtomicReference<Scoreboard> board = new AtomicReference<>(mock(Scoreboard.class));
        AtomicReference<Location> at = new AtomicReference<>(new Location(world, 0, 64, 0));
        when(p.getUniqueId()).thenReturn(id);
        when(p.getName()).thenReturn(name);
        when(p.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(p.getScoreboard()).thenAnswer(i -> board.get());
        doAnswer(i -> {
            board.set(i.getArgument(0));
            return null;
        }).when(p).setScoreboard(org.mockito.ArgumentMatchers.any());
        when(p.getLocation()).thenAnswer(i -> at.get().clone());
        players.put(id, p);
        boards.put(id, board);
        locations.put(id, at);
    }

    Player get(UUID id) {
        return players.get(id);
    }

    void moveTo(UUID id, double x) {
        locations.get(id).set(new Location(world, x, 64, 0));
    }
}

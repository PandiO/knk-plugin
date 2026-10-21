package net.knightsandkings.knk.paper.siege;

import net.knightsandkings.knk.core.minigame.MinigameRegistry;
import net.knightsandkings.knk.core.minigame.Participation;
import net.knightsandkings.knk.core.minigame.Participation.Stage;
import net.knightsandkings.knk.core.siege.SiegeCombatRules;
import net.knightsandkings.knk.core.siege.SiegeMatchRoster;
import net.knightsandkings.knk.core.siege.SiegePhase;
import net.knightsandkings.knk.core.statistics.StatisticsContext;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Arena duels Phase 0 (KNG-209): siege seen through the minigame registry answers every shared hook as the siege
 * lambdas in KnKPlugin did (lobbyOf / isParticipant / activeLobbyOf / runningMatchOf, allowsCombat, headshots,
 * the outage release and resume).
 */
class SiegeParticipationTest {

    private final SiegeService service = mock(SiegeService.class);
    private final Map<UUID, Player> online = new HashMap<>();
    private final SiegeParticipation siege = new SiegeParticipation(service, online::get);
    private final UUID alice = UUID.randomUUID();

    private SiegeLobbyRuntime lobby(SiegePhase phase, String token, boolean aliceOnRoster) {
        SiegeLobbyRuntime lobby = mock(SiegeLobbyRuntime.class);
        when(lobby.phase()).thenReturn(phase);
        if (token == null) {
            when(lobby.match()).thenReturn(Optional.empty());
        } else {
            SiegeMatch match = mock(SiegeMatch.class);
            SiegeMatchRoster roster = mock(SiegeMatchRoster.class);
            when(match.matchToken()).thenReturn(token);
            when(match.roster()).thenReturn(roster);
            when(roster.contains(alice)).thenReturn(aliceOnRoster);
            when(lobby.match()).thenReturn(Optional.of(match));
        }
        return lobby;
    }

    /** Alice is a member of a lobby in this phase (built before stubbing, Mockito can't nest stubbings). */
    private void member(SiegePhase phase, String token, boolean onRoster) {
        SiegeLobbyRuntime built = lobby(phase, token, onRoster);
        when(service.lobbyOf(alice)).thenReturn(Optional.of(built));
    }

    private Player online(UUID id) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        online.put(id, player);
        return player;
    }

    @Test
    void theStagesMatchTheSiegePhaseRulesTheHooksUsedBefore() {
        for (SiegePhase phase : SiegePhase.values()) {
            for (boolean onRoster : new boolean[] {false, true}) {
                Stage stage = SiegeParticipation.stageOf(phase, onRoster);
                // teleport guard, /back, scoreboard, lootbox: activeLobbyOf
                assertEquals(phase.isMatchActive(), stage.isAway(), phase + " away");
                // discovery, /hub, domain-access respawn: isParticipant
                assertEquals(phase.blocksDiscovery(), stage.hasLeftOpenWorld(), phase + " left the open world");
                // statistics context: runningMatchOf
                assertEquals(phase == SiegePhase.IN_PROGRESS && onRoster, stage == Stage.PLAYING, phase + " playing");
            }
        }
    }

    @Test
    void anyLobbyMemberIsAParticipantWithTheMatchToken() {
        when(service.lobbyOf(alice)).thenReturn(Optional.empty());
        assertTrue(siege.participationOf(alice).isEmpty());

        member(SiegePhase.MATCHMAKING, null, false);
        assertEquals(new Participation(alice, "siege", null, Stage.QUEUED), siege.participationOf(alice).orElseThrow());

        member(SiegePhase.IN_PROGRESS, "m-1", true);
        assertEquals(new Participation(alice, "siege", "m-1", Stage.PLAYING), siege.participationOf(alice).orElseThrow());

        member(SiegePhase.IN_PROGRESS, "m-1", false);
        assertEquals(Stage.STAGING, siege.participationOf(alice).orElseThrow().stage(), "in the lobby, not on the roster");
    }

    @Test
    void throughTheRegistryTheHooksSeeTheSameMemberships() {
        MinigameRegistry registry = new MinigameRegistry();
        registry.register(siege);

        member(SiegePhase.HUB, null, false);
        assertTrue(registry.isMember(alice));
        assertTrue(registry.hasLeftOpenWorld(alice));
        assertTrue(registry.holdsLootDrops(alice) && registry.keepsOwnScoreboard(alice) && registry.excludesBackOnDeath(alice));
        assertEquals(Optional.of("You are in a siege."), registry.hubRefusal(alice));
        assertTrue(registry.statisticsContextOf(alice).isEmpty());

        member(SiegePhase.IN_PROGRESS, "m-1", true);
        assertEquals(Optional.of(StatisticsContext.SIEGE), registry.statisticsContextOf(alice));

        member(SiegePhase.COOLDOWN, "m-1", true);
        assertTrue(registry.hasLeftOpenWorld(alice));
        assertFalse(registry.holdsLootDrops(alice) || registry.keepsOwnScoreboard(alice));
    }

    @Test
    void combatAndHeadshotsAskTheSiegeRulesForOnlinePlayers() {
        UUID bob = UUID.randomUUID();
        Player a = online(alice);
        Player b = online(bob);
        when(service.allowsCombat(a, b)).thenReturn(true);
        when(service.combatantOf(a)).thenReturn(new SiegeCombatRules.Combatant(3, true, 1, false));
        when(service.combatantOf(b)).thenReturn(new SiegeCombatRules.Combatant(3, true, 2, false));
        when(service.headshotMultiplier(3)).thenReturn(1.75);

        assertTrue(siege.allowsCombat(alice, bob));
        assertFalse(siege.allowsCombat(bob, alice));
        assertFalse(siege.allowsCombat(alice, UUID.randomUUID()), "offline: no siege hit");
        assertEquals(1.75, siege.headshotMultiplier(alice, bob));

        when(service.combatantOf(b)).thenReturn(new SiegeCombatRules.Combatant(4, true, 2, false));
        assertEquals(1.0, siege.headshotMultiplier(alice, bob), "another lobby");
        when(service.combatantOf(b)).thenReturn(null);
        assertEquals(1.0, siege.headshotMultiplier(alice, bob), "not in a siege");
    }

    @Test
    void theOutageReleaseAndResumeAreTheSiegeOnes() {
        when(service.releaseForOutage(alice)).thenReturn(false);
        assertFalse(siege.releaseForOutage(alice));
        siege.resumeAfterOutage();
        verify(service).resumeAfterOutage();
        assertEquals(java.util.Set.of(SiegeTeleportRestriction.BYPASS_NODE), siege.teleportBypassNodes());
    }
}

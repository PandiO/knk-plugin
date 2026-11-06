package net.knightsandkings.knk.paper.siege;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.domain.gates.CachedGateStructure;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeGate;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchLength;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeObjective;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeTeam;
import net.knightsandkings.knk.core.domain.siege.SiegeGateState;
import net.knightsandkings.knk.core.domain.siege.SiegeTeamRole;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.core.ports.api.SiegeGatesCommandApi;
import net.knightsandkings.knk.paper.gates.HealthSystem;
import net.knightsandkings.knk.paper.siege.npc.MatchActor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Siege Survival link S3 (PandiO/knk-workspace#41): {@code SiegeGateController.mayDamage(Entity, int)} - a registered
 * match actor of the lockdown's lobby damages a locked gate by its team (an enemy of the owner), never its own
 * alliance's gate; strangers, other lobbies' actors and unattributed hits are refused as before; players keep the
 * player rule; unlocked gates take anything. Teams: Defenders 1 (alliance 1) own gate 10; NPCs 2 (alliance 2).
 */
class SiegeGateControllerNpcDamageTest {

    private static final int GATE = 10;
    private static final int UNLOCKED_GATE = 99;
    private static final int LOBBY = 7;

    private final GateManager gateManager = new GateManager();
    private final SiegeGateController controller;
    private final SiegeLobbyRuntime lobby = mock(SiegeLobbyRuntime.class);
    private MatchActor zombieActor;
    private MatchActor defenderActor;
    private MatchActor otherLobbyActor;

    SiegeGateControllerNpcDamageTest() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        when(plugin.isEnabled()).thenReturn(true);
        controller = new SiegeGateController(plugin, gateManager, mock(HealthSystem.class), mock(SiegeGatesCommandApi.class));
    }

    private static KnkSiegeScenario scenario() {
        List<KnkSiegeTeam> teams = List.of(
                new KnkSiegeTeam(1, 1, SiegeTeamRole.DEFENDER, 1, null, "Defenders", "WHITE", null, null, List.of()),
                new KnkSiegeTeam(2, 2, SiegeTeamRole.ATTACKER, 2, null, "NPCs", "WHITE", null, null, List.of()));
        List<KnkSiegeObjective> objectives = List.of(
                new KnkSiegeObjective(2, 1, "Gatehouse", null, GATE, 500, 2.5, false, 1, true, SiegeGateState.CLOSED));
        List<KnkSiegeGate> gates = List.of(new KnkSiegeGate(GATE, "Main gate", 1, SiegeGateState.CLOSED, true, true));
        return new KnkSiegeScenario(100, "S", null, 1, "Town", null, List.of(), null, 1, 20, null, null,
                KnkSiegeMatchLength.DEFAULT, null, true, true, true, teams, objectives, gates, List.of());
    }

    @BeforeEach
    void lockDown() {
        CachedGateDoor door = new CachedGateDoor(1, GATE, "Main gate", "SLIDING", "VERTICAL", "PLANE_GRID",
                60, 1, new Vector(100, 64, 100), 5, 5, 3, 500.0, 500.0, true, false, false, 90, "north");
        door.setCurrentState(AnimationState.CLOSED);
        gateManager.cacheGate(door);
        gateManager.cacheStructure(new CachedGateStructure(GATE, "Main gate"));

        when(lobby.id()).thenReturn(LOBBY);
        when(lobby.key()).thenReturn("castle");
        when(lobby.matchIdFuture()).thenReturn(null); // applied at once, no API hop
        when(lobby.isMember(org.mockito.ArgumentMatchers.any())).thenReturn(false);
        when(lobby.match()).thenReturn(Optional.empty());
        controller.areaLockdownStarted(lobby, scenario());
        assertTrue(controller.isLocked(GATE));

        zombieActor = new MatchActor(UUID.randomUUID(), LOBBY, 2, 1.0);
        defenderActor = new MatchActor(UUID.randomUUID(), LOBBY, 1, 1.0);
        otherLobbyActor = new MatchActor(UUID.randomUUID(), LOBBY + 1, 2, 1.0);
        controller.setMatchActors(entity -> {
            for (MatchActor actor : List.of(zombieActor, defenderActor, otherLobbyActor)) {
                if (actor.entityId().equals(entity.getUniqueId())) return Optional.of(actor);
            }
            return Optional.empty();
        });
    }

    private static Zombie zombie(UUID id) {
        Zombie zombie = mock(Zombie.class);
        when(zombie.getUniqueId()).thenReturn(id);
        return zombie;
    }

    @Test
    void anActorOfTheEnemyTeamMayDamageTheLockedGate_alsoThroughItsProjectile() {
        Zombie zombie = zombie(zombieActor.entityId());
        assertTrue(controller.mayDamage((Entity) zombie, GATE));

        Arrow arrow = mock(Arrow.class);
        when(arrow.getUniqueId()).thenReturn(UUID.randomUUID());
        when(arrow.getShooter()).thenReturn(zombie);
        assertTrue(controller.mayDamage((Entity) arrow, GATE));
    }

    @Test
    void anActorNeverDamagesItsOwnAllianceGate_andOtherLobbiesActorsAreStrangers() {
        assertFalse(controller.mayDamage((Entity) zombie(defenderActor.entityId()), GATE), "the owner's team");
        assertFalse(controller.mayDamage((Entity) zombie(otherLobbyActor.entityId()), GATE), "another match's NPC");
        assertFalse(controller.mayDamage((Entity) zombie(UUID.randomUUID()), GATE), "an ordinary mob, as before");
        assertFalse(controller.mayDamage((Entity) null, GATE), "unattributed, as before");
    }

    @Test
    void playersKeepThePlayerRule_andUnlockedGatesTakeAnything() {
        Player stranger = mock(Player.class);
        when(stranger.getUniqueId()).thenReturn(UUID.randomUUID());
        assertFalse(controller.mayDamage((Entity) stranger, GATE), "a non-member player, as mayDamage(Player, int)");
        assertFalse(controller.mayDamage(stranger, GATE));

        assertTrue(controller.mayDamage((Entity) zombie(UUID.randomUUID()), UNLOCKED_GATE));
        assertTrue(controller.mayDamage((Entity) null, UNLOCKED_GATE));
        assertTrue(controller.mayDamage((Player) null, UNLOCKED_GATE));
    }

    @Test
    void withoutARegistryNothingButPlayersIsAttributed() {
        controller.setMatchActors(null);
        assertFalse(controller.mayDamage((Entity) zombie(zombieActor.entityId()), GATE));
    }
}

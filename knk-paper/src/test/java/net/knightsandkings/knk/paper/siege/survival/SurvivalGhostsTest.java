package net.knightsandkings.knk.paper.siege.survival;

import net.knightsandkings.knk.core.survival.SurvivalSubstate;
import net.knightsandkings.knk.core.visibility.GhostTeam;
import net.knightsandkings.knk.paper.visibility.PlayerVisibilityArbiter;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** DESIGN §3b/S41/S60: who is a ghost, what happens to them, and when they come back. The World mock is a field (CLAUDE.md). */
class SurvivalGhostsTest {

    private final World world = mock(World.class);
    private final PlayerVisibilityArbiter arbiter = mock(PlayerVisibilityArbiter.class);
    private final GhostTeam team = new GhostTeam("siege:1:1", id -> true);
    private final Location spawn = new Location(world, 10, 64, 10);
    private final Map<UUID, Player> online = new HashMap<>();
    private final SurvivalGhosts ghosts = new SurvivalGhosts(arbiter, team, p -> Optional.of(spawn));

    private Player player() {
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(player.isOnline()).thenReturn(true);
        when(player.getFoodLevel()).thenReturn(20);
        online.put(id, player);
        return player;
    }

    @Test
    void aWaveDeathBecomesAGhostOnRespawnAndIsReleasedAtTheIntermission() {
        Player p = player();
        assertTrue(ghosts.onDeath(p, SurvivalSubstate.WAVE_ACTIVE));
        assertTrue(ghosts.isGhost(p.getUniqueId()));
        verify(arbiter, never()).markGhost(any(), any());

        assertTrue(ghosts.applyOnRespawn(p, SurvivalSubstate.WAVE_ACTIVE));
        verify(arbiter).markGhost(p, team);
        verify(p).setInvulnerable(true);
        verify(p).setCollidable(false);

        assertEquals(1, ghosts.onTransition(SurvivalSubstate.INTERMISSION, online::get).size());
        assertFalse(ghosts.isGhost(p.getUniqueId()));
        verify(arbiter).clearGhost(p);
        verify(p).setInvulnerable(false);
        verify(p).setCollidable(true);
        verify(p).teleport(spawn);
    }

    @Test
    void aBreakDeathWaitsForTheNextRoundAndNoGhostBeforeWaveOne() {
        Player p = player();
        assertTrue(ghosts.onDeath(p, SurvivalSubstate.INTERMISSION));
        ghosts.applyOnRespawn(p, SurvivalSubstate.INTERMISSION);
        assertTrue(ghosts.onTransition(SurvivalSubstate.INTERMISSION, online::get).isEmpty());
        assertTrue(ghosts.isGhost(p.getUniqueId()));
        assertEquals(1, ghosts.onTransition(SurvivalSubstate.WAVE_ACTIVE, online::get).size());
        assertFalse(ghosts.isGhost(p.getUniqueId()));

        Player early = player();
        assertFalse(ghosts.onDeath(early, SurvivalSubstate.PREPARING));
        assertFalse(ghosts.isGhost(early.getUniqueId()));
    }

    @Test
    void aLateRespawnClickAfterTheReleaseMomentIsAlive() {
        Player p = player();
        ghosts.onDeath(p, SurvivalSubstate.WAVE_ACTIVE);
        assertFalse(ghosts.applyOnRespawn(p, SurvivalSubstate.INTERMISSION), "the round ended while on the death screen");
        assertFalse(ghosts.isGhost(p.getUniqueId()));
        verify(arbiter, never()).markGhost(any(), any());
    }

    @Test
    void releaseAllAndLeaveEndTheStateWithoutTeleporting() {
        Player a = player();
        Player b = player();
        ghosts.onDeath(a, SurvivalSubstate.WAVE_ACTIVE);
        ghosts.applyOnRespawn(a, SurvivalSubstate.WAVE_ACTIVE);
        ghosts.onDeath(b, SurvivalSubstate.WAVE_ACTIVE);
        ghosts.release(b.getUniqueId(), b, false);
        assertFalse(ghosts.isGhost(b.getUniqueId()));
        verify(b, never()).teleport(any(Location.class));
        ghosts.releaseAll(online::get);
        assertEquals(0, ghosts.size());
        verify(arbiter).clearGhost(a);
        verify(a, never()).teleport(any(Location.class));
        verify(a).setInvulnerable(false);
    }

    @Test
    void applyEffectsKeepsFoodAndFireOff() {
        Player p = player();
        when(p.getFoodLevel()).thenReturn(3);
        ghosts.applyEffects(p);
        verify(p).setFoodLevel(20);
        verify(p).setFireTicks(0);
        verify(arbiter).markGhost(eq(p), eq(team));
    }
}

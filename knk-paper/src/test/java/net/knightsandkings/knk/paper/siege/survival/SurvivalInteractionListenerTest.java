package net.knightsandkings.knk.paper.siege.survival;

import net.knightsandkings.knk.core.survival.economy.KillBlow;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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

/** Player hits on match actors become kill blows with the critical flag and the head-band headshot (DESIGN §8). */
class SurvivalInteractionListenerTest {

    private final World world = mock(World.class);
    private final SurvivalMatchRuntime runtime = mock(SurvivalMatchRuntime.class);
    private final SurvivalMatch match = mock(SurvivalMatch.class);
    private final SurvivalInteractionListener listener = new SurvivalInteractionListener(runtime);
    private final Player alice = mock(Player.class);
    private final Zombie zombie = mock(Zombie.class);

    SurvivalInteractionListenerTest() {
        when(alice.getUniqueId()).thenReturn(UUID.randomUUID());
        when(zombie.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        when(zombie.getEyeHeight()).thenReturn(1.74);
        when(runtime.matchOfActor(zombie)).thenReturn(Optional.of(match));
    }

    private EntityDamageByEntityEvent event(org.bukkit.entity.Entity damager, boolean critical) {
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getEntity()).thenReturn(zombie);
        when(event.getDamager()).thenReturn(damager);
        when(event.isCritical()).thenReturn(critical);
        return event;
    }

    @Test
    void aMeleeCriticalIsRecorded() {
        listener.onPlayerHitsActor(event(alice, true));
        ArgumentCaptor<KillBlow> blow = ArgumentCaptor.forClass(KillBlow.class);
        verify(match).onMobHitByPlayer(eq(zombie), blow.capture());
        assertEquals(alice.getUniqueId(), blow.getValue().attacker());
        assertTrue(blow.getValue().critical());
        assertFalse(blow.getValue().headshot());
    }

    @Test
    void anArrowInTheHeadBandIsAHeadshotByItsShooter() {
        Arrow arrow = mock(Arrow.class);
        when(arrow.getShooter()).thenReturn(alice);
        when(arrow.getLocation()).thenReturn(new Location(world, 0, 65.6, 0));
        listener.onPlayerHitsActor(event(arrow, false));
        ArgumentCaptor<KillBlow> blow = ArgumentCaptor.forClass(KillBlow.class);
        verify(match).onMobHitByPlayer(eq(zombie), blow.capture());
        assertTrue(blow.getValue().headshot());
        assertEquals(alice.getUniqueId(), blow.getValue().attacker());

        when(arrow.getLocation()).thenReturn(new Location(world, 0, 64.9, 0));
        listener.onPlayerHitsActor(event(arrow, false));
        verify(match, org.mockito.Mockito.times(2)).onMobHitByPlayer(eq(zombie), blow.capture());
        assertFalse(blow.getValue().headshot());
    }

    @Test
    void hitsByMobsOrOnNonActorsAreIgnored() {
        listener.onPlayerHitsActor(event(mock(Zombie.class), true));
        verify(match, never()).onMobHitByPlayer(any(), any());
        when(runtime.matchOfActor(zombie)).thenReturn(Optional.empty());
        listener.onPlayerHitsActor(event(alice, true));
        verify(match, never()).onMobHitByPlayer(any(), any());
    }
}

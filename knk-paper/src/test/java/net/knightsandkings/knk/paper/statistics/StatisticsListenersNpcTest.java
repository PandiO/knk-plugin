package net.knightsandkings.knk.paper.statistics;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.UUID;

import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.paper.config.KnkConfig;
import net.knightsandkings.knk.paper.npc.nav.NpcEntities;

/**
 * NAV-08 (PandiO/knk-workspace#490): NPCs never reach the statistics service: no fall records, no combat counters,
 * no deaths, kills or arrows, and an NPC's attack is no AFK activity. Each case has a player counterpart that still
 * reaches the service. Bukkit's damage and death events need the server's registries, so they are mocked.
 */
class StatisticsListenersNpcTest {

    private final StatisticsService service = mock(StatisticsService.class);

    private static Player player(boolean npc) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.hasMetadata(NpcEntities.METADATA_KEY)).thenReturn(npc);
        return player;
    }

    private static EntityDamageByEntityEvent hit(Entity attacker, Entity victim) {
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getDamager()).thenReturn(attacker);
        when(event.getEntity()).thenReturn(victim);
        when(event.getCause()).thenReturn(EntityDamageEvent.DamageCause.ENTITY_ATTACK);
        when(event.getFinalDamage()).thenReturn(4.0);
        return event;
    }

    private CombatStatisticsListener combat() {
        return new CombatStatisticsListener(service, KnkConfig.StatisticsConfig.CombatConfig.defaults(), (a, v) -> 1.0);
    }

    // ---- FallStatisticsListener ----

    @Test
    void anNpcFallIsNotRecorded() {
        Player npc = player(true);
        EntityDamageEvent fall = mock(EntityDamageEvent.class);
        when(fall.getEntity()).thenReturn(npc);
        when(fall.getCause()).thenReturn(EntityDamageEvent.DamageCause.FALL);
        new FallStatisticsListener(service).onDamage(fall);
        verifyNoInteractions(service);
    }

    @Test
    void aPlayerFallStillIs() {
        Player player = player(false);
        EntityDamageEvent fall = mock(EntityDamageEvent.class);
        when(fall.getEntity()).thenReturn(player);
        when(fall.getCause()).thenReturn(EntityDamageEvent.DamageCause.FALL);
        new FallStatisticsListener(service).onDamage(fall);
        verify(service).hasSession(player.getUniqueId());
    }

    // ---- AfkActivityListener ----

    @Test
    void anNpcAttackIsNoActivity() {
        new AfkActivityListener(service, Runnable::run, Clock.systemUTC()).onAttack(hit(player(true), mock(Zombie.class)));
        verifyNoInteractions(service);
    }

    @Test
    void aPlayerAttackStillIs() {
        Player player = player(false);
        new AfkActivityListener(service, Runnable::run, Clock.systemUTC()).onAttack(hit(player, mock(Zombie.class)));
        verify(service).activity(player);
    }

    // ---- CombatStatisticsListener ----

    @Test
    void hitsByOrOnAnNpcAreNotCounted() {
        Zombie zombie = mock(Zombie.class);
        when(zombie.getHealth()).thenReturn(20.0);
        Player npc = player(true);
        when(npc.getHealth()).thenReturn(20.0);
        CombatStatisticsListener listener = combat();
        listener.onDamage(hit(player(false), npc));
        listener.onDamage(hit(npc, player(false)));
        listener.onDamage(hit(npc, zombie));
        verifyNoInteractions(service);
    }

    @Test
    void aPlayerHittingAMobStillCounts() {
        Zombie zombie = mock(Zombie.class);
        when(zombie.getHealth()).thenReturn(20.0);
        Player player = player(false);
        combat().onDamage(hit(player, zombie));
        verify(service).excluded(player);
    }

    @Test
    void anNpcDeathIsNoPlayerDeath() {
        PlayerDeathEvent death = mock(PlayerDeathEvent.class);
        Player npc = player(true);
        Player killer = player(false);
        when(death.getEntity()).thenReturn(npc);
        when(npc.getKiller()).thenReturn(killer);
        combat().onPlayerDeath(death);
        verifyNoInteractions(service);
    }

    @Test
    void aPlayerDeathStillCounts() {
        PlayerDeathEvent death = mock(PlayerDeathEvent.class);
        Player player = player(false);
        when(death.getEntity()).thenReturn(player);
        combat().onPlayerDeath(death);
        verify(service).excluded(player);
    }

    @Test
    void aMobKilledByAnNpcIsNoPveKill() {
        EntityDeathEvent death = mock(EntityDeathEvent.class);
        Zombie zombie = mock(Zombie.class);
        Player npc = player(true);
        when(zombie.getKiller()).thenReturn(npc);
        when(death.getEntity()).thenReturn(zombie);
        combat().onEntityDeath(death);
        verifyNoInteractions(service);
    }

    @Test
    void aMobKilledByAPlayerStillAsksTheService() {
        EntityDeathEvent death = mock(EntityDeathEvent.class);
        Zombie zombie = mock(Zombie.class);
        Player player = player(false);
        when(zombie.getKiller()).thenReturn(player);
        when(death.getEntity()).thenReturn(zombie);
        combat().onEntityDeath(death);
        verify(service).excluded(player);
    }

    @Test
    void anNpcArrowIsNotCounted() {
        Player npc = player(true);
        EntityShootBowEvent shot = mock(EntityShootBowEvent.class);
        when(shot.getEntity()).thenReturn(npc);
        when(shot.getProjectile()).thenReturn(mock(Arrow.class));
        combat().onShoot(shot);
        verifyNoInteractions(service);
    }

    @Test
    void aPlayerArrowStillIs() {
        EntityShootBowEvent shot = mock(EntityShootBowEvent.class);
        Player player = player(false);
        when(shot.getEntity()).thenReturn(player);
        when(shot.getProjectile()).thenReturn(mock(Arrow.class));
        combat().onShoot(shot);
        verify(service).excluded(player);
    }
}

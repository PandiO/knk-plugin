package net.knightsandkings.knk.paper.statistics;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.statistics.StatisticsBatch;
import net.knightsandkings.knk.core.statistics.StatisticsBuffer;
import net.knightsandkings.knk.core.statistics.StatisticsMetric;
import net.knightsandkings.knk.paper.config.KnkConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Siege Survival link S3 (PandiO/knk-workspace#41): a Survival match NPC stays out of the ordinary PvE statistics -
 * no PvE kill, no mob damage dealt or received, no death cause - while an ordinary zombie next to it counts exactly
 * as before. The World mock is a field (knk-plugin CLAUDE.md).
 */
class CombatStatisticsMatchNpcTest {

    private static final Instant T0 = Instant.parse("2026-11-06T10:00:00Z");

    private final World world = mock(World.class);
    private final StatisticsBuffer buffer = new StatisticsBuffer();
    private final Set<UUID> inMatch = new HashSet<>();
    private final Set<UUID> matchNpcs = new HashSet<>();
    private final StatisticsService service = new StatisticsService(KnkConfig.StatisticsConfig.defaults(), buffer,
            new StatisticsContextResolver(inMatch::contains), mock(AfkPresentation.class), Clock.fixed(T0, ZoneOffset.UTC));
    private final CombatStatisticsListener listener = new CombatStatisticsListener(service,
            KnkConfig.StatisticsConfig.CombatConfig.defaults(), (attacker, victim) -> 1.0, this::isMatchNpc);

    private final Player alice = player("Alice", 11);
    private final Zombie npc = zombie(true);
    private final Zombie wild = zombie(false);

    private boolean isMatchNpc(Entity entity) {
        if (matchNpcs.contains(entity.getUniqueId())) return true;
        return entity instanceof Arrow arrow && arrow.getShooter() instanceof Entity shooter && matchNpcs.contains(shooter.getUniqueId());
    }

    private Player player(String name, int userId) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn(name);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.getHealth()).thenReturn(20.0);
        when(player.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        service.sessionStarted(player, userId);
        return player;
    }

    private Zombie zombie(boolean matchNpc) {
        Zombie zombie = mock(Zombie.class);
        UUID id = UUID.randomUUID();
        when(zombie.getUniqueId()).thenReturn(id);
        when(zombie.getHealth()).thenReturn(20.0);
        when(zombie.getEntitySpawnReason()).thenReturn(CreatureSpawnEvent.SpawnReason.CUSTOM);
        if (matchNpc) matchNpcs.add(id);
        return zombie;
    }

    private static EntityDamageByEntityEvent hit(Entity damager, Entity victim, double finalDamage) {
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getDamager()).thenReturn(damager);
        when(event.getEntity()).thenReturn(victim);
        when(event.getFinalDamage()).thenReturn(finalDamage);
        when(event.getCause()).thenReturn(DamageCause.ENTITY_ATTACK);
        return event;
    }

    private static EntityDeathEvent creatureDeath(Zombie zombie, Player killer) {
        when(zombie.getKiller()).thenReturn(killer);
        EntityDeathEvent event = mock(EntityDeathEvent.class);
        when(event.getEntity()).thenReturn(zombie);
        return event;
    }

    private static PlayerDeathEvent death(Player victim, EntityDamageEvent lastDamage) {
        when(victim.getKiller()).thenReturn(null);
        when(victim.getLastDamageCause()).thenReturn(lastDamage);
        PlayerDeathEvent event = mock(PlayerDeathEvent.class);
        when(event.getEntity()).thenReturn(victim);
        return event;
    }

    private StatisticsBatch drain() {
        StatisticsBatch batch = buffer.drain(2_000, T0, UUID::randomUUID);
        return batch != null ? batch : new StatisticsBatch(UUID.randomUUID(), T0, null, null, null, null, null);
    }

    private static double total(List<StatisticsBatch.ValueEntry> entries, StatisticsMetric metric) {
        return entries.stream().filter(e -> e.metric().equals(metric.key())).mapToDouble(StatisticsBatch.ValueEntry::value).sum();
    }

    @Test
    void killingAMatchNpcIsNoPveKill_killingAnOrdinaryZombieStillIs() {
        listener.onEntityDeath(creatureDeath(npc, alice));
        listener.onEntityDeath(creatureDeath(wild, alice));

        assertEquals(1.0, total(drain().counters(), StatisticsMetric.PVE_KILLS));
    }

    @Test
    void damageWithAMatchNpcIsNotMobDamage() {
        listener.onDamage(hit(alice, npc, 6.0));
        listener.onDamage(hit(npc, alice, 4.0));
        Arrow arrow = mock(Arrow.class);
        when(arrow.getUniqueId()).thenReturn(UUID.randomUUID());
        when(arrow.getShooter()).thenReturn(npc);
        listener.onDamage(hit(arrow, alice, 2.0));

        listener.onDamage(hit(alice, wild, 6.0));
        listener.onDamage(hit(wild, alice, 4.0));

        StatisticsBatch batch = drain();
        assertEquals(6.0, total(batch.counters(), StatisticsMetric.DAMAGE_DEALT_MOB), "the wild zombie only");
        assertEquals(4.0, total(batch.counters(), StatisticsMetric.DAMAGE_RECEIVED_MOB), "the wild zombie only");
    }

    @Test
    void aDeathToAMatchNpcHasNoOrdinaryDeathCause_butIsStillADeath() {
        listener.onPlayerDeath(death(alice, hit(npc, alice, 3.0)));

        StatisticsBatch batch = drain();
        assertEquals(0.0, total(batch.counters(), StatisticsMetric.DEATHS_BY_CAUSE_MOB));
        assertEquals(0.0, total(batch.counters(), StatisticsMetric.DEATHS_BY_CAUSE_PLAYER));
        assertEquals(0.0, total(batch.counters(), StatisticsMetric.DEATHS_BY_CAUSE_ENVIRONMENT));
        assertEquals(1.0, total(batch.counters(), StatisticsMetric.DEATHS), "an open-world death is still a death");

        listener.onPlayerDeath(death(alice, hit(wild, alice, 3.0)));
        assertEquals(1.0, total(drain().counters(), StatisticsMetric.DEATHS_BY_CAUSE_MOB), "an ordinary zombie, as before");
    }

    @Test
    void withoutAMatchNpcPredicateEverythingCountsAsBefore() {
        CombatStatisticsListener plain = new CombatStatisticsListener(service, KnkConfig.StatisticsConfig.CombatConfig.defaults(),
                (attacker, victim) -> 1.0);
        plain.onEntityDeath(creatureDeath(npc, alice));
        plain.onDamage(hit(npc, alice, 4.0));

        StatisticsBatch batch = drain();
        assertEquals(1.0, total(batch.counters(), StatisticsMetric.PVE_KILLS));
        assertEquals(4.0, total(batch.counters(), StatisticsMetric.DAMAGE_RECEIVED_MOB));
    }
}

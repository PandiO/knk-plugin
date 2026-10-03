package net.knightsandkings.knk.paper.statistics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Trident;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.domain.statistics.StatisticsBatch;
import net.knightsandkings.knk.core.gates.GateFireAttribution;
import net.knightsandkings.knk.core.statistics.StatisticsBuffer;
import net.knightsandkings.knk.core.statistics.StatisticsMetric;
import net.knightsandkings.knk.paper.config.KnkConfig;

/**
 * Plan §8 link 4 criteria 1-4: combat facts per context (damage split and capped, CUSTOM excluded,
 * headshots only where a multiplier applies, deaths with causes, PvE spawn-reason filter, arrows),
 * running-match members' kills/deaths/streaks left to the projection, the open-world killstreak, and
 * the gate-damage sink (attacker / shooter / TNT source, fire per igniter incl. offline igniters,
 * the gate's context). Damage events can't be built without a server, so they are mocked; the World
 * mock is held in a field (knk-plugin CLAUDE.md).
 */
class CombatStatisticsListenerTest {

    private static final Instant T0 = Instant.parse("2026-10-03T10:00:00Z");

    private final World world = mock(World.class);
    private final StatisticsBuffer buffer = new StatisticsBuffer();
    private final Set<UUID> inMatch = new HashSet<>();
    private final StatisticsService service = new StatisticsService(KnkConfig.StatisticsConfig.defaults(), buffer,
            new StatisticsContextResolver(inMatch::contains), mock(AfkPresentation.class), Clock.fixed(T0, ZoneOffset.UTC));
    private double headshotMultiplier = 1.0;
    private final CombatStatisticsListener listener = new CombatStatisticsListener(service,
            KnkConfig.StatisticsConfig.CombatConfig.defaults(), (attacker, victim) -> headshotMultiplier);

    private final Player alice = player("Alice", 11);
    private final Player bob = player("Bob", 12);

    private Player player(String name, int userId) {
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn(name);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.getHealth()).thenReturn(20.0);
        when(player.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        service.sessionStarted(player, userId);
        return player;
    }

    private StatisticsBatch drain() {
        StatisticsBatch batch = buffer.drain(2_000, T0, UUID::randomUUID);
        return batch != null ? batch : new StatisticsBatch(UUID.randomUUID(), T0, null, null, null, null, null);
    }

    private static double total(List<StatisticsBatch.ValueEntry> entries, int userId, StatisticsMetric metric, String context) {
        return entries.stream()
                .filter(e -> e.userId() == userId && e.metric().equals(metric.key()) && e.context().equals(context))
                .mapToDouble(StatisticsBatch.ValueEntry::value).sum();
    }

    private static double total(List<StatisticsBatch.ValueEntry> entries, StatisticsMetric metric) {
        return entries.stream().filter(e -> e.metric().equals(metric.key())).mapToDouble(StatisticsBatch.ValueEntry::value).sum();
    }

    private static EntityDamageByEntityEvent hit(Entity damager, Entity victim, double finalDamage, DamageCause cause) {
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getDamager()).thenReturn(damager);
        when(event.getEntity()).thenReturn(victim);
        when(event.getFinalDamage()).thenReturn(finalDamage);
        when(event.getCause()).thenReturn(cause);
        return event;
    }

    private Arrow arrowFrom(Object shooter, double y) {
        Arrow arrow = mock(Arrow.class);
        when(arrow.getShooter()).thenReturn((org.bukkit.projectiles.ProjectileSource) shooter);
        when(arrow.getLocation()).thenReturn(new Location(world, 0, y, 0));
        return arrow;
    }

    private static PlayerDeathEvent death(Player victim, Player killer, EntityDamageEvent lastDamage) {
        when(victim.getKiller()).thenReturn(killer);
        when(victim.getLastDamageCause()).thenReturn(lastDamage);
        PlayerDeathEvent event = mock(PlayerDeathEvent.class);
        when(event.getEntity()).thenReturn(victim);
        return event;
    }

    // ===== damage =====

    @Test
    void playerVsPlayerDamageIsDealtAndReceivedInTheOpenWorld() {
        listener.onDamage(hit(alice, bob, 6.0, DamageCause.ENTITY_ATTACK));

        StatisticsBatch batch = drain();
        assertEquals(6.0, total(batch.counters(), 11, StatisticsMetric.DAMAGE_DEALT_PLAYER, "open_world"));
        assertEquals(6.0, total(batch.counters(), 12, StatisticsMetric.DAMAGE_RECEIVED_PLAYER, "open_world"));
    }

    @Test
    void damageIsCappedAtWhatTheVictimHadLeft() {
        when(bob.getHealth()).thenReturn(3.0);
        when(bob.getAbsorptionAmount()).thenReturn(1.0);

        listener.onDamage(hit(alice, bob, 30.0, DamageCause.ENTITY_ATTACK));

        StatisticsBatch batch = drain();
        assertEquals(4.0, total(batch.counters(), 11, StatisticsMetric.DAMAGE_DEALT_PLAYER, "open_world"));
        assertEquals(4.0, total(batch.counters(), 12, StatisticsMetric.DAMAGE_RECEIVED_PLAYER, "open_world"));
    }

    @Test
    void creaturesSplitDealtAndReceivedIntoTheMobMetrics() {
        Zombie zombie = mock(Zombie.class);
        when(zombie.getHealth()).thenReturn(20.0);
        Skeleton skeleton = mock(Skeleton.class);

        listener.onDamage(hit(alice, zombie, 5.0, DamageCause.ENTITY_ATTACK));
        listener.onDamage(hit(zombie, alice, 3.0, DamageCause.ENTITY_ATTACK));
        listener.onDamage(hit(arrowFrom(skeleton, 64.5), alice, 2.0, DamageCause.PROJECTILE));

        StatisticsBatch batch = drain();
        assertEquals(5.0, total(batch.counters(), 11, StatisticsMetric.DAMAGE_DEALT_MOB, "open_world"));
        assertEquals(5.0, total(batch.counters(), 11, StatisticsMetric.DAMAGE_RECEIVED_MOB, "open_world"));
        assertEquals(0.0, total(batch.counters(), StatisticsMetric.DAMAGE_DEALT_PLAYER));
    }

    @Test
    void customDamageSelfDamageArmorStandsAndExcludedModesCountNothing() {
        ArmorStand stand = mock(ArmorStand.class);
        when(stand.getHealth()).thenReturn(20.0);
        Player creative = player("Creative", 13);
        when(creative.getGameMode()).thenReturn(GameMode.CREATIVE);

        listener.onDamage(hit(alice, bob, 6.0, DamageCause.CUSTOM));
        listener.onDamage(hit(arrowFrom(alice, 64), alice, 2.0, DamageCause.PROJECTILE));
        listener.onDamage(hit(alice, stand, 6.0, DamageCause.ENTITY_ATTACK));
        listener.onDamage(hit(creative, bob, 6.0, DamageCause.ENTITY_ATTACK));
        listener.onDamage(hit(mock(org.bukkit.entity.FallingBlock.class), bob, 6.0, DamageCause.FALLING_BLOCK));

        StatisticsBatch batch = drain();
        assertEquals(0.0, total(batch.counters(), StatisticsMetric.DAMAGE_DEALT_PLAYER));
        assertEquals(0.0, total(batch.counters(), StatisticsMetric.DAMAGE_DEALT_MOB));
        // the creative attacker's hit still counts for the survival victim
        assertEquals(6.0, total(batch.counters(), 12, StatisticsMetric.DAMAGE_RECEIVED_PLAYER, "open_world"));
        assertEquals(0.0, total(batch.counters(), StatisticsMetric.DAMAGE_RECEIVED_MOB));
    }

    @Test
    void customDamageCountsWhenConfigured() {
        CombatStatisticsListener counting = new CombatStatisticsListener(service,
                new KnkConfig.StatisticsConfig.CombatConfig(true, true, Set.of()), null);

        counting.onDamage(hit(alice, bob, 6.0, DamageCause.CUSTOM));

        assertEquals(6.0, total(drain().counters(), 11, StatisticsMetric.DAMAGE_DEALT_PLAYER, "open_world"));
    }

    @Test
    void siegeMembersDamageIsStoredInTheSiegeContext() {
        inMatch.add(alice.getUniqueId());
        inMatch.add(bob.getUniqueId());

        listener.onDamage(hit(alice, bob, 6.0, DamageCause.ENTITY_ATTACK));

        StatisticsBatch batch = drain();
        assertEquals(6.0, total(batch.counters(), 11, StatisticsMetric.DAMAGE_DEALT_PLAYER, "siege"));
        assertEquals(6.0, total(batch.counters(), 12, StatisticsMetric.DAMAGE_RECEIVED_PLAYER, "siege"));
    }

    // ===== headshots =====

    @Test
    void headshotsCountOnlyAboveTheBodyLineWhereAMultiplierApplies() {
        listener.onDamage(hit(arrowFrom(alice, 66.0), bob, 4.0, DamageCause.PROJECTILE));
        assertEquals(0.0, total(drain().counters(), StatisticsMetric.HEADSHOTS), "no multiplier in the open world");

        headshotMultiplier = 1.5;
        listener.onDamage(hit(arrowFrom(alice, 66.0), bob, 4.0, DamageCause.PROJECTILE));
        listener.onDamage(hit(arrowFrom(alice, 64.5), bob, 4.0, DamageCause.PROJECTILE));
        listener.onDamage(hit(alice, bob, 4.0, DamageCause.ENTITY_ATTACK));

        StatisticsBatch batch = drain();
        assertEquals(1.0, total(batch.counters(), 11, StatisticsMetric.HEADSHOTS, "open_world"));
        assertEquals(12.0, total(batch.counters(), 11, StatisticsMetric.DAMAGE_DEALT_PLAYER, "open_world"));
    }

    // ===== deaths, kills, streaks =====

    @Test
    void anOpenWorldPvpKillRecordsTheKillTheDeathTheCauseAndTheStreak() {
        listener.onPlayerDeath(death(bob, alice, hit(alice, bob, 5.0, DamageCause.ENTITY_ATTACK)));

        StatisticsBatch batch = drain();
        assertEquals(List.of(new StatisticsBatch.PvpKillEntry(11, 12, "open_world", T0)), batch.pvpKills());
        assertEquals(1.0, total(batch.counters(), 12, StatisticsMetric.DEATHS, "open_world"));
        assertEquals(1.0, total(batch.counters(), 12, StatisticsMetric.DEATHS_BY_CAUSE_PLAYER, "open_world"));
        assertEquals(1.0, total(batch.records(), 11, StatisticsMetric.HIGHEST_KILLSTREAK, "open_world"));
    }

    @Test
    void theStreakGrowsWithKillsAndResetsOnDeathAndQuit() {
        Player carol = player("Carol", 13);
        listener.onPlayerDeath(death(bob, alice, null));
        listener.onPlayerDeath(death(carol, alice, null));
        assertEquals(2, listener.streaks().current(alice.getUniqueId()));
        assertEquals(2.0, drain().records().stream().mapToDouble(StatisticsBatch.ValueEntry::value).max().orElse(0));

        listener.onPlayerDeath(death(alice, null, null));
        assertEquals(0, listener.streaks().current(alice.getUniqueId()), "any death resets");

        listener.onPlayerDeath(death(bob, alice, null));
        PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
        when(quit.getPlayer()).thenReturn(alice);
        listener.onQuit(quit);
        assertEquals(0, listener.streaks().current(alice.getUniqueId()), "quitting resets (L1-11)");
    }

    @Test
    void deathCausesMobAndEnvironment() {
        Zombie zombie = mock(Zombie.class);
        EntityDamageEvent lava = mock(EntityDamageEvent.class);

        listener.onPlayerDeath(death(alice, null, hit(zombie, alice, 3.0, DamageCause.ENTITY_ATTACK)));
        listener.onPlayerDeath(death(bob, null, lava));
        listener.onPlayerDeath(death(bob, bob, hit(arrowFrom(bob, 64), bob, 3.0, DamageCause.PROJECTILE)));

        StatisticsBatch batch = drain();
        assertEquals(1.0, total(batch.counters(), 11, StatisticsMetric.DEATHS_BY_CAUSE_MOB, "open_world"));
        assertEquals(2.0, total(batch.counters(), 12, StatisticsMetric.DEATHS_BY_CAUSE_ENVIRONMENT, "open_world"),
                "a self-kill is no player death and gives no kill");
        assertEquals(3.0, total(batch.counters(), StatisticsMetric.DEATHS));
        assertTrue(batch.pvpKills().isEmpty());
    }

    @Test
    void runningMatchMembersKillsDeathsAndStreaksAreLeftToTheProjection() {
        inMatch.add(alice.getUniqueId());
        inMatch.add(bob.getUniqueId());

        listener.onPlayerDeath(death(bob, alice, hit(alice, bob, 5.0, DamageCause.ENTITY_ATTACK)));

        StatisticsBatch batch = drain();
        assertTrue(batch.pvpKills().isEmpty());
        assertTrue(batch.records().isEmpty());
        assertEquals(0.0, total(batch.counters(), StatisticsMetric.DEATHS));
        assertEquals(1.0, total(batch.counters(), 12, StatisticsMetric.DEATHS_BY_CAUSE_PLAYER, "siege"),
                "the internal cause is recorded in every context");
        assertEquals(0, listener.streaks().current(alice.getUniqueId()), "a Siege kill doesn't feed the open-world streak");
    }

    @Test
    void anExcludedVictimRecordsNothingButLosesTheStreak() {
        listener.onPlayerDeath(death(bob, alice, null));
        when(alice.getGameMode()).thenReturn(GameMode.SPECTATOR);
        drain();

        listener.onPlayerDeath(death(alice, bob, null));

        StatisticsBatch batch = drain();
        assertTrue(batch.counters().isEmpty());
        assertTrue(batch.pvpKills().isEmpty());
        assertEquals(0, listener.streaks().current(alice.getUniqueId()));
    }

    // ===== PvE kills and arrows =====

    private EntityDeathEvent creatureDeath(Player killer, CreatureSpawnEvent.SpawnReason reason) {
        Zombie zombie = mock(Zombie.class);
        when(zombie.getKiller()).thenReturn(killer);
        when(zombie.getEntitySpawnReason()).thenReturn(reason);
        EntityDeathEvent event = mock(EntityDeathEvent.class);
        when(event.getEntity()).thenReturn(zombie);
        return event;
    }

    @Test
    void pveKillsSkipFarmedCreaturesAndKillerlessDeaths() {
        listener.onEntityDeath(creatureDeath(alice, CreatureSpawnEvent.SpawnReason.NATURAL));
        listener.onEntityDeath(creatureDeath(alice, CreatureSpawnEvent.SpawnReason.SPAWNER));
        listener.onEntityDeath(creatureDeath(alice, CreatureSpawnEvent.SpawnReason.BREEDING));
        listener.onEntityDeath(creatureDeath(null, CreatureSpawnEvent.SpawnReason.NATURAL));
        listener.onEntityDeath(creatureDeath(alice, null));

        assertEquals(2.0, total(drain().counters(), 11, StatisticsMetric.PVE_KILLS, "open_world"));
    }

    @Test
    void playersAndArmorStandsAreNoPveKills() {
        ArmorStand stand = mock(ArmorStand.class);
        when(stand.getKiller()).thenReturn(alice);
        EntityDeathEvent standDeath = mock(EntityDeathEvent.class);
        when(standDeath.getEntity()).thenReturn(stand);
        EntityDeathEvent playerDeath = mock(EntityDeathEvent.class);
        when(bob.getKiller()).thenReturn(alice);
        when(playerDeath.getEntity()).thenReturn(bob);

        listener.onEntityDeath(standDeath);
        listener.onEntityDeath(playerDeath);

        assertEquals(0.0, total(drain().counters(), StatisticsMetric.PVE_KILLS));
    }

    private static EntityShootBowEvent shot(Entity shooter, Entity projectile) {
        EntityShootBowEvent event = mock(EntityShootBowEvent.class);
        when(event.getEntity()).thenReturn((org.bukkit.entity.LivingEntity) shooter);
        when(event.getProjectile()).thenReturn(projectile);
        return event;
    }

    @Test
    void arrowsAreCountedOnShootWithoutTridentsFireworksOrCreatures() {
        listener.onShoot(shot(alice, mock(Arrow.class)));
        listener.onShoot(shot(alice, mock(Arrow.class)));
        listener.onShoot(shot(alice, mock(Trident.class)));
        listener.onShoot(shot(alice, mock(Firework.class)));
        listener.onShoot(shot(mock(Skeleton.class), mock(Arrow.class)));

        assertEquals(2.0, total(drain().counters(), 11, StatisticsMetric.ARROWS_FIRED, "open_world"));
    }

    // ===== gate-damage sink =====

    private static CachedGateDoor gate(int structureId) {
        return new CachedGateDoor(1, structureId, "Gate", "SLIDING", "VERTICAL", "PLANE_GRID",
                60, 1, new Vector(0, 64, 0), 5, 5, 3, 100.0, 100.0, true, false, false, 90, "north");
    }

    @Test
    void gateDamageIsCreditedToTheAttackerTheShooterOrTheTntSourceInTheGatesContext() {
        GateDamageStatisticsSink sink = new GateDamageStatisticsSink(service, true);
        sink.setLockedByMatch(structureId -> structureId == 5);
        TNTPrimed tnt = mock(TNTPrimed.class);
        when(tnt.getSource()).thenReturn(bob);

        sink.directDamage(gate(9), alice, 10.0);
        sink.directDamage(gate(5), arrowFrom(alice, 64), 4.0);
        sink.directDamage(gate(9), tnt, 7.5);
        sink.directDamage(gate(9), mock(TNTPrimed.class), 10.0);
        sink.directDamage(gate(9), null, 10.0);

        StatisticsBatch batch = drain();
        assertEquals(10.0, total(batch.counters(), 11, StatisticsMetric.GATE_DAMAGE, "open_world"));
        assertEquals(4.0, total(batch.counters(), 11, StatisticsMetric.GATE_DAMAGE, "siege"));
        assertEquals(7.5, total(batch.counters(), 12, StatisticsMetric.GATE_DAMAGE, "open_world"));
        assertEquals(21.5, total(batch.counters(), StatisticsMetric.GATE_DAMAGE));
    }

    @Test
    void fireIsCreditedPerIgniterEvenAfterTheyLoggedOff() {
        GateDamageStatisticsSink sink = new GateDamageStatisticsSink(service, true);
        GateFireAttribution.Igniter igniter = sink.igniterOf(arrowFrom(alice, 64));
        assertEquals(new GateFireAttribution.Igniter(alice.getUniqueId(), 11), igniter);
        assertNull(sink.igniterOf(mock(Zombie.class)));

        sink.fireDamage(gate(9), igniter, 3.0);
        service.sessionEnded(alice);
        sink.fireDamage(gate(9), igniter, 2.0);
        sink.fireDamage(gate(9), new GateFireAttribution.Igniter(UUID.randomUUID(), 0), 9.0);

        StatisticsBatch batch = drain();
        assertEquals(5.0, total(batch.counters(), 11, StatisticsMetric.GATE_DAMAGE, "open_world"));
        assertEquals(5.0, total(batch.counters(), StatisticsMetric.GATE_DAMAGE), "an igniter without a known user id is credited to nobody");
    }

    @Test
    void fireAttributionCanBeSwitchedOff() {
        assertTrue(new GateDamageStatisticsSink(service, true).tracksFire());
        assertEquals(false, new GateDamageStatisticsSink(service, false).tracksFire());
    }
}

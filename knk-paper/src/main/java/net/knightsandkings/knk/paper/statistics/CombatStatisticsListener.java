package net.knightsandkings.knk.paper.statistics;

import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.projectiles.ProjectileSource;

import net.knightsandkings.knk.core.siege.SiegeCombatRules;
import net.knightsandkings.knk.core.statistics.CombatStatisticsRules;
import net.knightsandkings.knk.core.statistics.DeathCauseClassifier;
import net.knightsandkings.knk.core.statistics.KillstreakTracker;
import net.knightsandkings.knk.core.statistics.StatisticsContext;
import net.knightsandkings.knk.core.statistics.StatisticsMetric;
import net.knightsandkings.knk.paper.config.KnkConfig;
import net.knightsandkings.knk.paper.siege.SiegeService;

/**
 * Combat statistics (KNG-34, DESIGN.md §F.7, IMPLEMENTATION_PLAN.md §5.2 link 4), all observed at
 * {@code MONITOR} so they see the outcome every other listener decided and change nothing:
 * <ul>
 *   <li>damage dealt/received per player/creature, capped at the victim's remaining health+absorption,
 *       synthetic {@code CUSTOM} damage excluded by default (L1-9);</li>
 *   <li>headshots - projectile hits above the body line where a headshot multiplier applies (today:
 *       enemies in one Siege lobby with a multiplier &gt; 1), recomputed here instead of being edited
 *       into {@code SiegeCombatListener};</li>
 *   <li>deaths with their internal cause, PvP kills and the open-world killstreak - for a member of a
 *       running Siege match the kill, the death and the streak are the match roster's and projected by
 *       the API (§F.6), so only the cause is sent;</li>
 *   <li>PvE kills without farmed creatures (L1-10) and arrows fired.</li>
 * </ul>
 * Players in an excluded game mode record nothing here. Registered only with
 * {@code statistics.enabled} and {@code statistics.combat.enabled}.
 */
public final class CombatStatisticsListener implements Listener {

    /** The headshot multiplier that applies to a projectile hit of {@code attacker} on {@code victim} (1 = none). */
    @FunctionalInterface
    public interface HeadshotMultiplier {
        double between(Player attacker, Player victim);
    }

    private final StatisticsService service;
    private final boolean countCustomDamage;
    private final Set<String> pveExcludedSpawnReasons;
    private final HeadshotMultiplier headshots;
    private final KillstreakTracker streaks = new KillstreakTracker();

    public CombatStatisticsListener(StatisticsService service, KnkConfig.StatisticsConfig.CombatConfig config,
                                    HeadshotMultiplier headshots) {
        this.service = service;
        this.countCustomDamage = config.countCustomDamage();
        this.pveExcludedSpawnReasons = config.pveExcludedSpawnReasons();
        this.headshots = headshots == null ? (attacker, victim) -> 1.0 : headshots;
    }

    /**
     * Siege's rule (DESIGN §6.7 of the Siege spec): both players in one lobby, that lobby's multiplier.
     * The service is created after statistics start and is null when Siege is off, so it is looked up per hit.
     */
    public static HeadshotMultiplier siegeHeadshots(Supplier<SiegeService> siegeService) {
        return (attacker, victim) -> {
            SiegeService siege = siegeService.get();
            if (siege == null) {
                return 1.0;
            }
            SiegeCombatRules.Combatant a = siege.combatantOf(attacker);
            SiegeCombatRules.Combatant v = siege.combatantOf(victim);
            return a != null && v != null && a.lobbyId() == v.lobbyId() ? siege.headshotMultiplier(v.lobbyId()) : 1.0;
        };
    }

    KillstreakTracker streaks() {
        return streaks;
    }

    // ===== damage and headshots =====

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity victim) || victim instanceof ArmorStand) {
            return;
        }
        if (!CombatStatisticsRules.countsDamageCause(causeName(event.getCause()), countCustomDamage)) {
            return;
        }
        Entity damager = event.getDamager();
        Player attacker = playerBehind(damager);
        if (attacker != null && attacker.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }
        boolean byMob = attacker == null && mobBehind(damager);
        if (attacker == null && !byMob) {
            return;
        }
        double amount = CombatStatisticsRules.cappedDamage(event.getFinalDamage(), victim.getHealth(), victim.getAbsorptionAmount());
        if (!(amount > 0)) {
            return;
        }
        Player playerVictim = victim instanceof Player p ? p : null;
        if (attacker != null && !service.excluded(attacker)) {
            service.addCounter(attacker, playerVictim != null ? StatisticsMetric.DAMAGE_DEALT_PLAYER : StatisticsMetric.DAMAGE_DEALT_MOB, amount);
            if (playerVictim != null && damager instanceof Projectile projectile && isHeadshot(projectile, attacker, playerVictim)) {
                service.addCounter(attacker, StatisticsMetric.HEADSHOTS, 1);
            }
        }
        if (playerVictim != null && !service.excluded(playerVictim)) {
            service.addCounter(playerVictim, attacker != null ? StatisticsMetric.DAMAGE_RECEIVED_PLAYER : StatisticsMetric.DAMAGE_RECEIVED_MOB, amount);
        }
    }

    private boolean isHeadshot(Projectile projectile, Player attacker, Player victim) {
        return SiegeCombatRules.isHeadshot(projectile.getLocation().getY(), victim.getLocation().getY())
                && headshots.between(attacker, victim) > 1.0;
    }

    // ===== deaths, PvP kills, killstreaks =====

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        UUID victimId = victim.getUniqueId();
        streaks.death(victimId);
        if (service.excluded(victim)) {
            return;
        }
        Player killer = victim.getKiller();
        boolean otherKiller = killer != null && !killer.getUniqueId().equals(victimId);
        boolean victimInMatch = StatisticsContext.SIEGE.equals(service.contextOf(victim));

        DeathCauseClassifier.Cause cause = DeathCauseClassifier.classify(otherKiller, lastDamager(victim));
        service.addCounter(victim, cause.metric(), 1);
        if (!victimInMatch) {
            service.addCounter(victim, StatisticsMetric.DEATHS, 1);
        }
        if (otherKiller && !victimInMatch && !service.excluded(killer)
                && !StatisticsContext.SIEGE.equals(service.contextOf(killer))) {
            service.pvpKill(killer, victim);
            service.addRecord(killer, StatisticsMetric.HIGHEST_KILLSTREAK, streaks.kill(killer.getUniqueId()));
        }
    }

    /** L1-11: the open-world streak ends with the session. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        streaks.quit(event.getPlayer().getUniqueId());
    }

    private DeathCauseClassifier.LastDamager lastDamager(Player victim) {
        EntityDamageEvent last = victim.getLastDamageCause();
        if (!(last instanceof EntityDamageByEntityEvent byEntity)) {
            return DeathCauseClassifier.LastDamager.NONE;
        }
        Player player = playerBehind(byEntity.getDamager());
        if (player != null) {
            return player.getUniqueId().equals(victim.getUniqueId())
                    ? DeathCauseClassifier.LastDamager.NONE : DeathCauseClassifier.LastDamager.PLAYER;
        }
        return mobBehind(byEntity.getDamager()) ? DeathCauseClassifier.LastDamager.MOB : DeathCauseClassifier.LastDamager.NONE;
    }

    // ===== PvE kills and arrows =====

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity instanceof Player || entity instanceof ArmorStand) {
            return;
        }
        Player killer = entity.getKiller();
        if (killer == null || service.excluded(killer)) {
            return;
        }
        CreatureSpawnEvent.SpawnReason reason = entity.getEntitySpawnReason();
        if (CombatStatisticsRules.countsPveKill(reason == null ? null : reason.name(), pveExcludedSpawnReasons)) {
            service.addCounter(killer, StatisticsMetric.PVE_KILLS, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player shooter) || service.excluded(shooter)) {
            return;
        }
        Entity projectile = event.getProjectile();
        if (CombatStatisticsRules.isArrow(projectile instanceof AbstractArrow, projectile instanceof Trident)) {
            service.addCounter(shooter, StatisticsMetric.ARROWS_FIRED, 1);
        }
    }

    // ===== attribution =====

    /** The player behind a damager: itself, a projectile's shooter or a primed TNT's source. Null otherwise. */
    static Player playerBehind(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player source) {
            return source;
        }
        return null;
    }

    /** A creature (non-player living entity) directly or by its projectile. */
    static boolean mobBehind(Entity damager) {
        if (damager instanceof Player) {
            return false;
        }
        if (damager instanceof LivingEntity) {
            return true;
        }
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            return shooter instanceof LivingEntity && !(shooter instanceof Player);
        }
        return false;
    }

    private static String causeName(EntityDamageEvent.DamageCause cause) {
        return cause == null ? null : cause.name();
    }
}

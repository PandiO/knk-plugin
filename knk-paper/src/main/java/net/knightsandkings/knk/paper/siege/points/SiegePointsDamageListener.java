package net.knightsandkings.knk.paper.siege.points;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * Feeds the assist log (siege shop P13, PandiO/knk-workspace#866): every player-on-player hit that went through
 * ({@code MONITOR}, ignoring cancelled hits, so the siege combat rules and the headshot multiplier have already
 * applied) is reported with its final damage. Observes only; registered with {@code siege.points.enabled}.
 */
public final class SiegePointsDamageListener implements Listener {

    private final SiegePointsService points;

    public SiegePointsDamageListener(SiegePointsService points) {
        this.points = points;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = attackerOf(event.getDamager());
        if (attacker == null) return;
        points.recordDamage(attacker, victim, event.getFinalDamage());
    }

    /** The player behind a hit: the damager, or a projectile's shooter; null otherwise. */
    static Player attackerOf(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) return shooter;
        return null;
    }
}

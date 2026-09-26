package net.knightsandkings.knk.paper.listeners;

import java.util.Objects;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import net.knightsandkings.knk.paper.teleport.TeleportService;

/**
 * Combat tag for teleports (docs/specs/teleport/DESIGN.md §3.4.3): a player hitting another player,
 * directly or with a projectile, tags both for {@code teleport.combat-tag-seconds} (v1's 10 s
 * {@code Main.combat}, set but never checked in v1). Player teleports refuse while tagged; staff
 * teleports ignore it. Hits that were cancelled (safezones, frozen players) don't tag.
 */
public class CombatTagListener implements Listener {

    private final TeleportService teleportService;

    public CombatTagListener(TeleportService teleportService) {
        this.teleportService = Objects.requireNonNull(teleportService, "teleportService must not be null");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = attackerOf(event.getDamager());
        if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }
        long now = teleportService.now();
        teleportService.combatTags().tag(victim.getUniqueId(), now);
        teleportService.combatTags().tag(attacker.getUniqueId(), now);
    }

    static Player attackerOf(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }
}

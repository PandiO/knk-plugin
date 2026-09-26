package net.knightsandkings.knk.paper.listeners;

import net.knightsandkings.knk.core.siege.SiegeCombatRules;
import net.knightsandkings.knk.core.siege.SiegeCombatRules.Combatant;
import net.knightsandkings.knk.core.siege.SiegeCombatRules.Outcome;
import net.knightsandkings.knk.paper.siege.SiegeMessages;
import net.knightsandkings.knk.paper.siege.SiegeService;
import net.kyori.adventure.text.Component;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.UUID;
import java.util.function.Predicate;

/**
 * Siege combat (DESIGN §6.7), {@code EntityDamageByEntityEvent} at {@code HIGHEST}:
 * <ul>
 *   <li>the real attacker is resolved from projectiles, null-safe (v2 NPE'd on shooterless projectiles);</li>
 *   <li>members of one running match may hit only enemies (different alliance groups), and never
 *       inside their own team's spawn safe zone - allowed hits are explicitly un-cancelled so region
 *       PvP flags don't interfere, <b>without changing any WorldGuard flag</b>;</li>
 *   <li>a siege member and anyone outside their match can never hurt each other;</li>
 *   <li>projectile hits between enemies above the body line get the headshot multiplier.</li>
 * </ul>
 * Hits involving no siege member are left alone. A player who is admin-frozen or still loading keeps
 * the cancellation those features set (they run at HIGHEST/LOWEST too): siege never un-cancels them.
 */
public class SiegeCombatListener implements Listener {

    private final SiegeService service;
    private final Predicate<UUID> damageLocked;

    /** @param damageLocked players whose damage another feature blocks (admin freeze, join loading) */
    public SiegeCombatListener(SiegeService service, Predicate<UUID> damageLocked) {
        this.service = service;
        this.damageLocked = damageLocked == null ? id -> false : damageLocked;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Projectile projectile = event.getDamager() instanceof Projectile p ? p : null;
        Player attacker = resolveAttacker(event.getDamager());
        if (attacker == null || attacker.equals(victim)) return;

        Combatant a = service.combatantOf(attacker);
        Combatant v = service.combatantOf(victim);
        if (a == null && v == null) return;
        Outcome outcome = SiegeCombatRules.decide(a, v,
                a != null && v != null && a.lobbyId() == v.lobbyId() ? service.alliancesOf(v.lobbyId()) : null);

        switch (outcome) {
            case NOT_SIEGE -> { }
            case ALLOW -> {
                if (damageLocked.test(attacker.getUniqueId()) || damageLocked.test(victim.getUniqueId())) return;
                event.setCancelled(false);
                if (projectile != null && SiegeCombatRules.isHeadshot(projectile.getLocation().getY(), victim.getLocation().getY())) {
                    double multiplier = service.headshotMultiplier(v.lobbyId());
                    if (multiplier > 1.0) {
                        event.setDamage(SiegeCombatRules.headshotDamage(event.getDamage(), multiplier));
                        attacker.sendActionBar(Component.text("Headshot!", SiegeMessages.HIGHLIGHT));
                        victim.sendActionBar(Component.text("You got headshot!", SiegeMessages.BAD));
                    }
                }
            }
            case DENY_VICTIM_SAFE -> deny(event, attacker, "You can't hurt players inside their spawn area!");
            case DENY_ATTACKER_SAFE -> deny(event, attacker, "You can't hurt players while you are inside your spawn area!");
            case DENY_ALLY -> deny(event, attacker, null);
            case DENY_NOT_STARTED -> deny(event, attacker, "The siege hasn't started yet.");
            case DENY_MEMBER_VS_NON_MEMBER -> deny(event, attacker, a != null
                    ? "In a siege you can only fight enemies in your own match."
                    : "You can't attack players who are in a siege.");
        }
    }

    private static void deny(EntityDamageByEntityEvent event, Player attacker, String message) {
        event.setCancelled(true);
        attacker.playSound(attacker.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
        if (message != null) attacker.sendActionBar(Component.text(message, SiegeMessages.BAD));
    }

    /** The player behind the hit: the damager itself, or a projectile's shooter. Null otherwise. */
    static Player resolveAttacker(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) return player;
        }
        return null;
    }
}

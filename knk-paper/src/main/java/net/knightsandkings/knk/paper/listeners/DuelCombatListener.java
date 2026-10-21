package net.knightsandkings.knk.paper.listeners;

import net.knightsandkings.knk.core.duel.DuelCombatRules;
import net.knightsandkings.knk.core.duel.DuelCombatRules.Combatant;
import net.knightsandkings.knk.core.duel.DuelCombatRules.Outcome;
import net.knightsandkings.knk.paper.duel.DuelService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Sound;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * Duel combat (arena duels DESIGN §8.1, D8), {@code EntityDamageByEntityEvent}:
 * <ul>
 *   <li>at {@code HIGHEST}: the real attacker is resolved (projectile shooter, a tamed pet's owner, the source of
 *       primed TNT or a lingering cloud) and P3's {@link DuelCombatRules#decide} applies - the fighting pair of one
 *       match is explicitly un-cancelled (overriding town, district and hub denies), everything else involving a
 *       duelist is cancelled both ways (no third-party interference, V7);</li>
 *   <li>at {@code MONITOR}: the fight telemetry (attempts, hits, damage) for the review signals.</li>
 * </ul>
 * Hits involving no duelist are left alone. An admin-frozen or loading player keeps the cancellation those features
 * set: duels never un-cancel them.
 */
public class DuelCombatListener implements Listener {

    private final DuelService service;
    private final Predicate<UUID> damageLocked;
    private final BiConsumer<Player, String> denied;

    /** @param damageLocked players whose damage another feature blocks (admin freeze, join loading) */
    public DuelCombatListener(DuelService service, Predicate<UUID> damageLocked) {
        this(service, damageLocked, DuelCombatListener::tellDenied);
    }

    /** @param denied how the attacker of a denied hit is told (tests: no sound registry without a server) */
    public DuelCombatListener(DuelService service, Predicate<UUID> damageLocked, BiConsumer<Player, String> denied) {
        this.service = service;
        this.damageLocked = damageLocked == null ? id -> false : damageLocked;
        this.denied = denied;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = resolveAttacker(event.getDamager());
        if (attacker == null || attacker.equals(victim)) return;
        Combatant a = service.combatantOf(attacker.getUniqueId());
        Combatant v = service.combatantOf(victim.getUniqueId());
        Outcome outcome = DuelCombatRules.decide(a, v);
        switch (outcome) {
            case NOT_DUEL -> { }
            case ALLOW -> {
                if (damageLocked.test(attacker.getUniqueId()) || damageLocked.test(victim.getUniqueId())) return;
                event.setCancelled(false);
            }
            case DENY_NOT_FIGHTING -> deny(event, attacker, "The fight hasn't started.");
            case DENY_OUTSIDE_MATCH -> deny(event, attacker, a != null
                    ? "In a duel you can only fight your opponent."
                    : "You can't attack players who are in a duel.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamageRecorded(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = resolveAttacker(event.getDamager());
        if (attacker == null || attacker.equals(victim)) return;
        service.recordHit(attacker.getUniqueId(), victim.getUniqueId(), !event.isCancelled(),
                event.isCancelled() ? 0 : event.getFinalDamage());
    }

    private void deny(EntityDamageByEntityEvent event, Player attacker, String message) {
        event.setCancelled(true);
        denied.accept(attacker, message);
    }

    private static void tellDenied(Player attacker, String message) {
        attacker.playSound(attacker.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
        attacker.sendActionBar(Component.text(message, NamedTextColor.RED));
    }

    /** The player behind the hit, or null. */
    static Player resolveAttacker(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile) return asPlayer(projectile.getShooter());
        if (damager instanceof Tameable pet && pet.isTamed() && pet.getOwner() instanceof Player owner) return owner;
        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player source) return source;
        if (damager instanceof AreaEffectCloud cloud) return asPlayer(cloud.getSource());
        return null;
    }

    private static Player asPlayer(ProjectileSource source) {
        return source instanceof Player player ? player : null;
    }
}

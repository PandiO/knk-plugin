package net.knightsandkings.knk.paper.enchantment.effects.impl;

import net.knightsandkings.knk.paper.enchantment.effects.SupportEnchantmentEffect;
import net.knightsandkings.knk.paper.regions.CombatSafezoneCheck;
import org.bukkit.Effect;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

public class FlashChaosEffect extends SupportEnchantmentEffect {
    private static final double DAMAGE_RADIUS = 3.0d;
    private static final double EFFECT_RADIUS = 5.0d;
    private static final double DAMAGE_AMOUNT = 60.0d;

    private final CombatSafezoneCheck safezones;

    public FlashChaosEffect(Plugin plugin) {
        this(plugin, CombatSafezoneCheck.NONE);
    }

    /** @param safezones players in a combat safezone get no damage, knockback or debuffs (KNG-11) */
    public FlashChaosEffect(Plugin plugin, CombatSafezoneCheck safezones) {
        super("flash_chaos", plugin);
        this.safezones = safezones != null ? safezones : CombatSafezoneCheck.NONE;
    }

    @Override
    protected void applyEffect(ItemStack item, Player player, int level) {
        Location playerLocation = player.getLocation();
        player.setFireTicks(0);
        playSound(playerLocation, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.0f, 1.0f);
        playEffect(playerLocation.clone().add(0.0d, 1.0d, 0.0d), Effect.FIREWORK_SHOOT, 0);

        for (Entity nearby : player.getNearbyEntities(EFFECT_RADIUS, EFFECT_RADIUS, EFFECT_RADIUS)) {
            if (!(nearby instanceof LivingEntity livingEntity) || livingEntity.getUniqueId().equals(player.getUniqueId())
                    || safezones.isProtected(player, livingEntity)) {
                continue;
            }

            boolean damageApplied = true;
            if (livingEntity.getLocation().distance(playerLocation) <= DAMAGE_RADIUS) {
                damageApplied = callDamageEventAndApply(player, livingEntity, DAMAGE_AMOUNT);
            }

            if (!damageApplied) {
                continue;
            }

            applyKnockback(player, livingEntity);
            if (livingEntity instanceof Player targetPlayer) {
                targetPlayer.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 100, 0));
                targetPlayer.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 250, 1));
            }
        }
    }

    private boolean callDamageEventAndApply(Player attacker, LivingEntity target, double damage) {
        EntityDamageByEntityEvent damageEvent = new EntityDamageByEntityEvent(
                attacker,
                target,
                EntityDamageEvent.DamageCause.CUSTOM,
                damage
        );
        plugin().getServer().getPluginManager().callEvent(damageEvent);

        if (damageEvent.isCancelled()) {
            return false;
        }

        target.damage(damageEvent.getFinalDamage(), attacker);
        return true;
    }

    private void applyKnockback(Player attacker, LivingEntity target) {
        Vector direction = target.getLocation().toVector().subtract(attacker.getLocation().toVector());
        if (direction.lengthSquared() <= 0.0001d) {
            direction = new Vector(0.01d, 0.0d, 0.01d);
        }

        Vector velocity = direction.normalize().multiply(1.2d);
        velocity.setY(0.4d);
        target.setVelocity(velocity);
    }
}

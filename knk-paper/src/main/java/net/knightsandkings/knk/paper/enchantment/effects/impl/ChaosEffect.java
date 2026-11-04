package net.knightsandkings.knk.paper.enchantment.effects.impl;

import net.knightsandkings.knk.core.domain.enchantment.AreaBlast;
import net.knightsandkings.knk.core.domain.enchantment.EnchantmentTuning;
import net.knightsandkings.knk.paper.enchantment.GateAreaDamage;
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
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

public class ChaosEffect extends SupportEnchantmentEffect {
    // PandiO/knk-workspace#223: damage, gate damage and both radii are combat values (EnchantmentTuning.area()), read
    // per activation; built-in 40 damage within 3 blocks, 40 to gates, knockback within 5.

    private final CombatSafezoneCheck safezones;
    private final Supplier<GateAreaDamage> gateAreaDamage;

    public ChaosEffect(Plugin plugin) {
        this(plugin, CombatSafezoneCheck.NONE);
    }

    /** @param safezones players in a combat safezone are neither damaged nor knocked back (KNG-11) */
    public ChaosEffect(Plugin plugin, CombatSafezoneCheck safezones) {
        this(plugin, safezones, () -> GateAreaDamage.NONE);
    }

    /**
     * @param gateAreaDamage how the blast reaches gate doors in range, with this enchantment's gate damage; read per
     *                       activation because the gates start after the enchantments
     */
    public ChaosEffect(Plugin plugin, CombatSafezoneCheck safezones, Supplier<GateAreaDamage> gateAreaDamage) {
        super("chaos", plugin);
        this.safezones = safezones != null ? safezones : CombatSafezoneCheck.NONE;
        this.gateAreaDamage = gateAreaDamage != null ? gateAreaDamage : () -> GateAreaDamage.NONE;
    }

    @Override
    protected void applyEffect(ItemStack item, Player player, int level) {
        Location playerLocation = player.getLocation();
        player.setFireTicks(0);
        playSound(playerLocation, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.0f, 1.0f);
        playEffect(playerLocation.clone().add(0.0d, 1.0d, 0.0d), Effect.FIREWORK_SHOOT, 0);
        blast(player, level);
    }

    /** The blast itself, without sound and particles: damage, knockback and gate damage around the user (#223). */
    void blast(Player player, int level) {
        EnchantmentTuning tuning = tuning();
        AreaBlast blast = tuning.area();
        double damage = tuning.areaDamageAt(level);
        double reach = blast.reach();
        Location playerLocation = player.getLocation();

        Set<LivingEntity> knockedBackEntities = new HashSet<>();
        for (Entity nearby : player.getNearbyEntities(reach, reach, reach)) {
            if (!(nearby instanceof LivingEntity livingEntity) || livingEntity.getUniqueId().equals(player.getUniqueId())
                    || safezones.isProtected(player, livingEntity)) {
                continue;
            }

            double distance = livingEntity.getLocation().distance(playerLocation);
            if (damage > 0.0d && distance <= blast.damageRadius()) {
                boolean damageApplied = callDamageEventAndApply(player, livingEntity, damage);
                if (!damageApplied) {
                    continue;
                }
            }

            applyKnockback(player, livingEntity);
            knockedBackEntities.add(livingEntity);
        }

        for (Entity nearby : player.getNearbyEntities(reach, reach, reach)) {
            if (!(nearby instanceof LivingEntity livingEntity)
                    || livingEntity.getUniqueId().equals(player.getUniqueId())
                    || knockedBackEntities.contains(livingEntity)
                    || safezones.isProtected(player, livingEntity)) {
                continue;
            }

            applyKnockback(player, livingEntity);
        }

        gateAreaDamage.get().damageGatesAround(player, playerLocation, tuning.gateDamageAt(level));
    }

    protected boolean callDamageEventAndApply(Player attacker, LivingEntity target, double damage) {
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

    protected void applyKnockback(Player attacker, LivingEntity target) {
        Vector direction = target.getLocation().toVector().subtract(attacker.getLocation().toVector());
        if (direction.lengthSquared() <= 0.0001d) {
            direction = new Vector(0.01d, 0.0d, 0.01d);
        }

        Vector velocity = direction.normalize().multiply(1.2d);
        velocity.setY(0.4d);
        target.setVelocity(velocity);
    }
}

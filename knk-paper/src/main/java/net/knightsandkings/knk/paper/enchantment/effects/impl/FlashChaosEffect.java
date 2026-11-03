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
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.function.Supplier;

public class FlashChaosEffect extends SupportEnchantmentEffect {
    // PandiO/knk-workspace#223: damage, gate damage and both radii are combat values (EnchantmentTuning.area()), read
    // per activation; built-in 60 damage within 3 blocks, 60 to gates, knockback and debuffs within 5.

    private final CombatSafezoneCheck safezones;
    private final Supplier<GateAreaDamage> gateAreaDamage;

    public FlashChaosEffect(Plugin plugin) {
        this(plugin, CombatSafezoneCheck.NONE);
    }

    /** @param safezones players in a combat safezone get no damage, knockback or debuffs (KNG-11) */
    public FlashChaosEffect(Plugin plugin, CombatSafezoneCheck safezones) {
        this(plugin, safezones, () -> GateAreaDamage.NONE);
    }

    /**
     * @param gateAreaDamage how the blast reaches gate doors in range, with this enchantment's gate damage; read per
     *                       activation because the gates start after the enchantments
     */
    public FlashChaosEffect(Plugin plugin, CombatSafezoneCheck safezones, Supplier<GateAreaDamage> gateAreaDamage) {
        super("flash_chaos", plugin);
        this.safezones = safezones != null ? safezones : CombatSafezoneCheck.NONE;
        this.gateAreaDamage = gateAreaDamage != null ? gateAreaDamage : () -> GateAreaDamage.NONE;
    }

    @Override
    protected void applyEffect(ItemStack item, Player player, int level) {
        // #201: the Slowness (duration + effect level) and Nausea (second duration) are combat values (#151), read per
        // activation; built-in Slowness I 5 s and Nausea 12.5 s. The bars below follow the same numbers.
        Location playerLocation = player.getLocation();
        player.setFireTicks(0);
        playSound(playerLocation, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.0f, 1.0f);
        playEffect(playerLocation.clone().add(0.0d, 1.0d, 0.0d), Effect.FIREWORK_SHOOT, 0);
        blast(player, level);
    }

    /** The blast itself, without sound and particles: damage, knockback, debuffs and gate damage (#223). */
    void blast(Player player, int level) {
        EnchantmentTuning tuning = tuning();
        Debuffs debuffs = Debuffs.of(tuning, level);
        AreaBlast blast = tuning.area();
        double damage = tuning.areaDamageAt(level);
        double reach = blast.reach();
        Location playerLocation = player.getLocation();

        for (Entity nearby : player.getNearbyEntities(reach, reach, reach)) {
            if (!(nearby instanceof LivingEntity livingEntity) || livingEntity.getUniqueId().equals(player.getUniqueId())
                    || safezones.isProtected(player, livingEntity)) {
                continue;
            }

            boolean damageApplied = true;
            if (damage > 0.0d && livingEntity.getLocation().distance(playerLocation) <= blast.damageRadius()) {
                damageApplied = callDamageEventAndApply(player, livingEntity, damage);
            }

            if (!damageApplied) {
                continue;
            }

            applyKnockback(player, livingEntity);
            if (livingEntity instanceof Player targetPlayer) {
                if (debuffs.slownessTicks() > 0) {
                    targetPlayer.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, debuffs.slownessTicks(),
                            debuffs.slownessAmplifier()));
                    display().debuff(targetPlayer, "slowness", "Slowness", PotionEffectType.SLOWNESS, debuffs.slownessTicks());
                }
                if (debuffs.nauseaTicks() > 0) {
                    targetPlayer.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, debuffs.nauseaTicks(),
                            NAUSEA_AMPLIFIER));
                    display().debuff(targetPlayer, "nausea", "Nausea", PotionEffectType.NAUSEA, debuffs.nauseaTicks());
                }
                // #169 round 2 (developer, 2026-10-27): no name-tag blackout here any more; only blindness hides tags.
            }
        }

        gateAreaDamage.get().damageGatesAround(player, playerLocation, tuning.gateDamageAt(level));
    }

    /**
     * Nausea II, as before #201. The amplifier isn't a combat value: the game draws Nausea the same at every level.
     */
    static final int NAUSEA_AMPLIFIER = 1;

    /** The debuffs one activation puts on players in range; a duration of 0 leaves that debuff out. */
    record Debuffs(int slownessTicks, int slownessAmplifier, int nauseaTicks) {
        static Debuffs of(EnchantmentTuning tuning, int level) {
            return new Debuffs(tuning.durationTicksAt(level), tuning.effectAmplifier(), tuning.secondaryDurationTicksAt(level));
        }
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

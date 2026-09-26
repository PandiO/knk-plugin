package net.knightsandkings.knk.paper.enchantment;

import net.knightsandkings.knk.core.domain.enchantment.EnchantmentRegistry;
import net.knightsandkings.knk.core.ports.enchantment.CooldownManager;
import net.knightsandkings.knk.core.ports.enchantment.EnchantmentExecutor;
import net.knightsandkings.knk.paper.enchantment.effects.AttackEnchantmentEffect;
import net.knightsandkings.knk.paper.enchantment.effects.SupportEnchantmentEffect;
import net.knightsandkings.knk.paper.enchantment.effects.impl.ArmorRepairEffect;
import net.knightsandkings.knk.paper.enchantment.effects.impl.BlindnessEffect;
import net.knightsandkings.knk.paper.enchantment.effects.impl.ChaosEffect;
import net.knightsandkings.knk.paper.enchantment.effects.impl.ConfusionEffect;
import net.knightsandkings.knk.paper.enchantment.effects.impl.FlashChaosEffect;
import net.knightsandkings.knk.paper.enchantment.effects.impl.FreezeEffect;
import net.knightsandkings.knk.paper.enchantment.effects.impl.HealthBoostEffect;
import net.knightsandkings.knk.paper.enchantment.effects.impl.InvisibilityEffect;
import net.knightsandkings.knk.paper.enchantment.effects.impl.PoisonEffect;
import net.knightsandkings.knk.paper.enchantment.effects.impl.ResistanceEffect;
import net.knightsandkings.knk.paper.enchantment.effects.impl.StrengthEffect;
import net.knightsandkings.knk.paper.enchantment.effects.impl.WitherEffect;
import net.knightsandkings.knk.paper.regions.CombatSafezoneCheck;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class ExecutorImpl implements EnchantmentExecutor {
    private final Plugin plugin;
    private final CooldownManager cooldownManager;
    private final Map<String, AttackEnchantmentEffect> attackEffects;
    private final Map<String, SupportEnchantmentEffect> supportEffects;

    public ExecutorImpl(Plugin plugin, CooldownManager cooldownManager, FrozenPlayerTracker frozenPlayerTracker) {
        this(plugin, cooldownManager, frozenPlayerTracker, CombatSafezoneCheck.NONE);
    }

    /**
     * @param safezones handed to the area abilities (chaos, flash_chaos) that hit nearby entities
     *                  themselves; attack enchantments are gated before they get here, in
     *                  {@code EnchantmentCombatListener} (KNG-11)
     */
    public ExecutorImpl(Plugin plugin, CooldownManager cooldownManager, FrozenPlayerTracker frozenPlayerTracker,
                        CombatSafezoneCheck safezones) {
        this.plugin = plugin;
        this.cooldownManager = cooldownManager;
        this.attackEffects = buildAttackEffects(plugin, frozenPlayerTracker);
        this.supportEffects = buildSupportEffects(plugin, safezones);
    }

    @Override
    public CompletableFuture<Void> executeOnMeleeHit(
            Map<String, Integer> enchantments,
            UUID attackerId,
            UUID targetId,
            double damageDealt
    ) {
        if (enchantments == null || enchantments.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        Player attacker = Bukkit.getPlayer(attackerId);
        Entity targetEntity = Bukkit.getEntity(targetId);
        if (attacker == null || !(targetEntity instanceof LivingEntity target)) {
            return CompletableFuture.completedFuture(null);
        }

        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        if (weapon == null || weapon.getType().isAir()) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            for (Map.Entry<String, Integer> entry : enchantments.entrySet()) {
                String enchantmentId = entry.getKey();
                int level = entry.getValue() == null ? 0 : entry.getValue();
                if (level < 1) {
                    continue;
                }

                AttackEnchantmentEffect effect = attackEffects.get(enchantmentId);
                if (effect == null) {
                    continue;
                }

                long remainingCooldown = cooldownManager.getRemainingCooldown(attackerId, enchantmentId).join();
                if (remainingCooldown > 0L) {
                    continue;
                }

                boolean triggered = effect.tryExecute(weapon, attacker, target, level);
                if (!triggered) {
                    continue;
                }

                EnchantmentRegistry.getInstance().getById(enchantmentId).ifPresent(definition -> {
                    if (definition.cooldownMs() > 0) {
                        cooldownManager.applyCooldown(attackerId, enchantmentId, definition.cooldownMs()).join();
                    }
                });
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> executeOnInteract(Map<String, Integer> enchantments, UUID playerId) {
        if (enchantments == null || enchantments.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }

        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            return CompletableFuture.completedFuture(false);
        }

        ItemStack itemInHand = player.getInventory().getItemInMainHand();
        if (itemInHand == null || itemInHand.getType().isAir()) {
            return CompletableFuture.completedFuture(false);
        }

        return CompletableFuture.supplyAsync(() -> {
            boolean triggeredAny = false;

            for (Map.Entry<String, Integer> entry : enchantments.entrySet()) {
                String enchantmentId = entry.getKey();
                int level = entry.getValue() == null ? 0 : entry.getValue();
                if (level < 1) {
                    continue;
                }

                SupportEnchantmentEffect effect = supportEffects.get(enchantmentId);
                if (effect == null) {
                    continue;
                }

                long remainingCooldown = cooldownManager.getRemainingCooldown(playerId, enchantmentId).join();
                if (remainingCooldown > 0L) {
                    continue;
                }

                boolean triggered = effect.tryExecute(itemInHand, player, level);
                if (!triggered) {
                    continue;
                }

                triggeredAny = true;
                EnchantmentRegistry.getInstance().getById(enchantmentId).ifPresent(definition -> {
                    if (definition.cooldownMs() > 0) {
                        cooldownManager.applyCooldown(playerId, enchantmentId, definition.cooldownMs()).join();
                    }
                });
            }

            return triggeredAny;
        });
    }

    @Override
    public CompletableFuture<Void> executeOnBowShoot(
            Map<String, Integer> enchantments,
            UUID shooterId,
            UUID projectileId
    ) {
        return CompletableFuture.completedFuture(null);
    }

    private static Map<String, AttackEnchantmentEffect> buildAttackEffects(Plugin plugin, FrozenPlayerTracker frozenPlayerTracker) {
        Map<String, AttackEnchantmentEffect> map = new LinkedHashMap<>();
        map.put("poison", new PoisonEffect(plugin));
        map.put("wither", new WitherEffect(plugin));
        map.put("freeze", new FreezeEffect(plugin, frozenPlayerTracker));
        map.put("blindness", new BlindnessEffect(plugin));
        map.put("confusion", new ConfusionEffect(plugin));
        map.put("strength", new StrengthEffect(plugin));
        return map;
    }

    private static Map<String, SupportEnchantmentEffect> buildSupportEffects(Plugin plugin, CombatSafezoneCheck safezones) {
        Map<String, SupportEnchantmentEffect> map = new LinkedHashMap<>();
        map.put("health_boost", new HealthBoostEffect(plugin));
        map.put("armor_repair", new ArmorRepairEffect(plugin));
        map.put("resistance", new ResistanceEffect(plugin));
        map.put("invisibility", new InvisibilityEffect(plugin));
        map.put("chaos", new ChaosEffect(plugin, safezones));
        map.put("flash_chaos", new FlashChaosEffect(plugin, safezones));
        return map;
    }
}

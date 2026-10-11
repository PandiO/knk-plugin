package net.knightsandkings.knk.paper.enchantment.effects.impl;

import net.knightsandkings.knk.paper.enchantment.effects.SupportEnchantmentEffect;
import org.bukkit.Effect;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

public class HealthBoostEffect extends SupportEnchantmentEffect {
    public HealthBoostEffect(Plugin plugin) {
        super("health_boost", plugin);
    }

    @Override
    protected void applyEffect(ItemStack item, Player player, int level) {
        new BukkitRunnable() {
            private int remainingIterations = 6;

            @Override
            public void run() {
                // Dying ends the heal: never set the health of a dead or just respawned player.
                if (!player.isOnline() || player.isDead()) {
                    cancel();
                    return;
                }

                double maxHealth = player.getAttribute(Attribute.MAX_HEALTH) != null
                        ? player.getAttribute(Attribute.MAX_HEALTH).getValue()
                        : player.getMaxHealth();
                double newHealth = Math.min(maxHealth, player.getHealth() + Math.max(level, 1));
                player.setHealth(newHealth);
                playEffect(player.getLocation().clone().add(0.0d, 1.5d, 0.0d), Effect.POTION_BREAK, 0);

                remainingIterations--;
                if (remainingIterations <= 0) {
                    cancel();
                }
            }
        }.runTaskTimer(plugin(), 0L, 5L);
    }
}

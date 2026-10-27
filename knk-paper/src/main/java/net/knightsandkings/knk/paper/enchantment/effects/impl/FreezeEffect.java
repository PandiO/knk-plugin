package net.knightsandkings.knk.paper.enchantment.effects.impl;

import net.knightsandkings.knk.paper.enchantment.FrozenPlayerTracker;
import net.knightsandkings.knk.paper.enchantment.effects.AttackEnchantmentEffect;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

public class FreezeEffect extends AttackEnchantmentEffect {
    private final FrozenPlayerTracker frozenPlayerTracker;

    public FreezeEffect(Plugin plugin, FrozenPlayerTracker frozenPlayerTracker) {
        super("freeze", plugin);
        this.frozenPlayerTracker = frozenPlayerTracker;
    }

    @Override
    protected void applyEffect(ItemStack weapon, Player attacker, LivingEntity target, int level) {
        int durationTicks = tuning().durationTicksAt(level);
        frozenPlayerTracker.freeze(target, durationTicks);
        display().debuff(target, "freeze", "Frozen", null, durationTicks);
        playSound(target.getLocation(), Sound.BLOCK_GLASS_BREAK, 0.8f, 1.2f);

        for (int tick = 0; tick <= durationTicks; tick += 20) {
            Bukkit.getScheduler().runTaskLater(plugin(), () -> {
                if (frozenPlayerTracker.isFrozen(target.getUniqueId()) && target.getWorld() != null) {
                    var center = target.getLocation().clone().add(0.0d, 0.6d, 0.0d);
                    target.getWorld().spawnParticle(
                            Particle.BLOCK,
                            center,
                            16,
                            0.35d,
                            0.45d,
                            0.35d,
                            Material.PACKED_ICE.createBlockData()
                    );
                    target.getWorld().spawnParticle(
                            Particle.SNOWFLAKE,
                            center,
                            10,
                            0.3d,
                            0.35d,
                            0.3d,
                            0.01d
                    );
                    playSound(center, Sound.BLOCK_GLASS_BREAK, 0.5f, 1.35f);
                }
            }, tick);
        }
    }
}

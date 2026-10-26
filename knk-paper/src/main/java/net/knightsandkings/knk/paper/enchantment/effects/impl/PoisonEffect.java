package net.knightsandkings.knk.paper.enchantment.effects.impl;

import net.knightsandkings.knk.paper.enchantment.effects.AttackEnchantmentEffect;
import org.bukkit.Effect;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.function.DoubleSupplier;

public class PoisonEffect extends AttackEnchantmentEffect {
    public PoisonEffect(Plugin plugin) {
        super("poison", plugin);
    }

    PoisonEffect(Plugin plugin, DoubleSupplier rollSupplier) {
        super("poison", plugin, rollSupplier);
    }

    @Override
    protected void applyEffect(ItemStack weapon, Player attacker, LivingEntity target, int level) {
        int durationTicks = tuning().durationTicksAt(level);
        target.addPotionEffect(new PotionEffect(PotionEffectType.POISON, durationTicks, 1));
        playEffect(target.getLocation(), Effect.POTION_BREAK, 0);
    }
}

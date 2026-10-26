package net.knightsandkings.knk.paper.enchantment.effects.impl;

import net.knightsandkings.knk.paper.enchantment.effects.AttackEnchantmentEffect;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public class BlindnessEffect extends AttackEnchantmentEffect {
    public BlindnessEffect(Plugin plugin) {
        super("blindness", plugin);
    }

    @Override
    protected void applyEffect(ItemStack weapon, Player attacker, LivingEntity target, int level) {
        int durationTicks = tuning().durationTicksAt(level);
        target.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, durationTicks, 0));
        playSound(target.getLocation(), Sound.ENTITY_WITHER_SPAWN, 1.0f, 1.0f);
    }
}

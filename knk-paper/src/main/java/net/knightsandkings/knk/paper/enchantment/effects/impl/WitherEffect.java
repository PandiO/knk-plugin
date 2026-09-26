package net.knightsandkings.knk.paper.enchantment.effects.impl;

import net.knightsandkings.knk.paper.enchantment.effects.AttackEnchantmentEffect;
import org.bukkit.Effect;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public class WitherEffect extends AttackEnchantmentEffect {
    public WitherEffect(Plugin plugin) {
        super("wither", 0.15d, plugin);
    }

    @Override
    protected void applyEffect(ItemStack weapon, Player attacker, LivingEntity target, int level) {
        int durationTicks = level * 40;
        target.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, durationTicks, 1));

        Location particleLocation = attacker.getLocation().clone().add(0.0d, 1.5d, 0.0d);
        playEffect(particleLocation, Effect.POTION_BREAK, 0);
    }
}

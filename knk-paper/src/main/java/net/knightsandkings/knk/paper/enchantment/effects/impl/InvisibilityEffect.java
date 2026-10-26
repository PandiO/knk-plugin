package net.knightsandkings.knk.paper.enchantment.effects.impl;

import net.knightsandkings.knk.paper.enchantment.effects.SupportEnchantmentEffect;
import org.bukkit.Effect;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public class InvisibilityEffect extends SupportEnchantmentEffect {
    public InvisibilityEffect(Plugin plugin) {
        super("invisibility", plugin);
    }

    @Override
    protected void applyEffect(ItemStack item, Player player, int level) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, tuning().durationTicksAt(level), 0));
        playEffect(player.getLocation().clone().add(0.0d, 1.0d, 0.0d), Effect.POTION_BREAK, 0);
    }
}

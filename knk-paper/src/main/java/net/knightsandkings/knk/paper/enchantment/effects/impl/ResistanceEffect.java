package net.knightsandkings.knk.paper.enchantment.effects.impl;

import net.knightsandkings.knk.paper.enchantment.effects.SupportEnchantmentEffect;
import org.bukkit.Effect;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public class ResistanceEffect extends SupportEnchantmentEffect {
    public ResistanceEffect(Plugin plugin) {
        super("resistance", plugin);
    }

    @Override
    protected void applyEffect(ItemStack item, Player player, int level) {
        int durationTicks = tuning().durationTicksAt(level);
        int amplifier = Math.max(level - 1, 0);
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, durationTicks, amplifier));
        playEffect(player.getLocation(), Effect.POTION_BREAK, 0);
    }
}

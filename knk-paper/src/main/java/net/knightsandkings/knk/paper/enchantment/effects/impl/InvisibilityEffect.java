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
        int durationTicks = tuning().durationTicksAt(level);
        // PandiO/knk-workspace#211: no potion swirls that give the invoker away (the HUD icon stays).
        player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, durationTicks, 0, false, false, true));
        // PandiO/knk-workspace#169: the invoker's own name tag stays hidden from other players while invisible;
        // #211: their armour and held items too (InvisibleEquipment), while they stay in the tab list.
        display().invisible(player, durationTicks);
        playEffect(player.getLocation().clone().add(0.0d, 1.0d, 0.0d), Effect.POTION_BREAK, 0);
    }
}

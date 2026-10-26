package net.knightsandkings.knk.paper.enchantment.effects;

import net.knightsandkings.knk.core.domain.enchantment.EnchantmentTuning;
import net.knightsandkings.knk.core.domain.enchantment.EnchantmentTuningTable;
import org.bukkit.Bukkit;
import org.bukkit.Effect;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Objects;

public abstract class SupportEnchantmentEffect implements EnchantmentEffect {
    private final String enchantmentId;
    private final Plugin plugin;

    protected SupportEnchantmentEffect(String enchantmentId, Plugin plugin) {
        this.enchantmentId = Objects.requireNonNull(enchantmentId, "enchantmentId must not be null");
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
    }

    @Override
    public final String enchantmentId() {
        return enchantmentId;
    }

    public final boolean tryExecute(ItemStack item, Player player, int level) {
        if (player == null || level < 1) {
            return false;
        }

        runOnMainThread(() -> applyEffect(item, player, level));
        return true;
    }

    @Override
    public final boolean tryExecute(ItemStack weapon, Player attacker, LivingEntity target, int level) {
        return tryExecute(weapon, attacker, level);
    }

    protected abstract void applyEffect(ItemStack item, Player player, int level);

    /** This ability's current combat values (PandiO/knk-workspace#151); right-click abilities don't roll a chance. */
    protected final EnchantmentTuning tuning() {
        return EnchantmentTuningTable.getInstance().get(enchantmentId);
    }

    protected final void runOnMainThread(Runnable runnable) {
        if (Bukkit.getServer() == null) {
            runnable.run();
            return;
        }

        if (Bukkit.isPrimaryThread()) {
            runnable.run();
            return;
        }

        Bukkit.getScheduler().runTask(plugin, runnable);
    }

    protected final void playEffect(Location location, Effect effect, int data) {
        if (location == null || location.getWorld() == null || effect == null) {
            return;
        }

        location.getWorld().playEffect(location, effect, data);
    }

    protected final void playSound(Location location, Sound sound, float volume, float pitch) {
        if (location == null || location.getWorld() == null || sound == null) {
            return;
        }

        location.getWorld().playSound(location, sound, volume, pitch);
    }

    protected final Plugin plugin() {
        return plugin;
    }
}
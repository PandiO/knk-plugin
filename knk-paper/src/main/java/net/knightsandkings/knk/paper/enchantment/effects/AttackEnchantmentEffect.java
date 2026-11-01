package net.knightsandkings.knk.paper.enchantment.effects;

import net.knightsandkings.knk.core.domain.enchantment.EnchantmentTuning;
import net.knightsandkings.knk.core.domain.enchantment.EnchantmentTuningTable;
import net.knightsandkings.knk.paper.enchantment.display.EffectDisplay;
import org.bukkit.Bukkit;
import org.bukkit.Effect;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * An enchantment effect that may go off when its holder hits something. The chance, duration and level scaling come
 * from {@link EnchantmentTuningTable} at the moment of the hit, so an admin's change in the web app applies without a
 * restart (PandiO/knk-workspace#151).
 */
public abstract class AttackEnchantmentEffect implements EnchantmentEffect {
    private final String enchantmentId;
    private final Plugin plugin;
    private final DoubleSupplier rollSupplier;
    private final Supplier<EnchantmentTuning> tuning;
    private volatile EffectDisplay display = EffectDisplay.NONE;

    protected AttackEnchantmentEffect(String enchantmentId, Plugin plugin) {
        this(enchantmentId, plugin, () -> ThreadLocalRandom.current().nextDouble());
    }

    protected AttackEnchantmentEffect(String enchantmentId, Plugin plugin, DoubleSupplier rollSupplier) {
        this(enchantmentId, plugin, rollSupplier, () -> EnchantmentTuningTable.getInstance().get(enchantmentId));
    }

    /** @param tuning read per hit; tests pass fixed values */
    protected AttackEnchantmentEffect(
            String enchantmentId,
            Plugin plugin,
            DoubleSupplier rollSupplier,
            Supplier<EnchantmentTuning> tuning
    ) {
        this.enchantmentId = Objects.requireNonNull(enchantmentId, "enchantmentId must not be null");
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
        this.rollSupplier = Objects.requireNonNull(rollSupplier, "rollSupplier must not be null");
        this.tuning = Objects.requireNonNull(tuning, "tuning must not be null");
    }

    @Override
    public final String enchantmentId() {
        return enchantmentId;
    }

    @Override
    public final boolean tryExecute(ItemStack weapon, Player attacker, LivingEntity target, int level) {
        if (!shouldTrigger(level)) {
            return false;
        }

        runOnMainThread(() -> applyEffect(weapon, attacker, target, level));
        return true;
    }

    protected abstract void applyEffect(ItemStack weapon, Player attacker, LivingEntity target, int level);

    protected final boolean shouldTrigger(int level) {
        if (level < 1) {
            return false;
        }

        double chance = tuning().chanceAt(level);
        if (chance >= 1.0d) {
            return true;
        }
        return rollSupplier.getAsDouble() < chance;
    }

    /** This enchantment's current combat values. */
    protected final EnchantmentTuning tuning() {
        EnchantmentTuning current = tuning.get();
        return current != null ? current : EnchantmentTuning.NONE;
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

    /**
     * Where this effect shows the victim a timed debuff (boss bar, PandiO/knk-workspace#170) or a name-tag blackout
     * while blinded (#169). {@link EffectDisplay#NONE} until set.
     */
    public final void setDisplay(EffectDisplay display) {
        this.display = display != null ? display : EffectDisplay.NONE;
    }

    protected final EffectDisplay display() {
        return display;
    }

    protected final Plugin plugin() {
        return plugin;
    }
}

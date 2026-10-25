package net.knightsandkings.knk.paper.gates;

import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import java.util.function.ToIntFunction;

/**
 * Siege playtest 2026-10-24: a melee hit on a gate door block with a Fire Aspect weapon has a chance of setting
 * that block alight (the same burn a flaming arrow starts, see {@link GateFireSystem}). The chance is
 * {@code chancePerLevel × level} - 15 % for Fire Aspect I and 30 % for II with the default - so a sword that
 * sets mobs on fire also, now and then, sets the gate on fire. The siege rules still decide whether the
 * ignition counts (a defender never burns their own gate; see {@code SiegeGateListener}).
 */
public class GateFireAspect {
    private final double chancePerLevel;
    private final ToIntFunction<ItemStack> fireAspectLevel;
    private final DoubleSupplier roll;

    /** @param chancePerLevel the ignition chance of Fire Aspect I; II doubles it (0 turns the mechanic off) */
    public GateFireAspect(double chancePerLevel) {
        this(chancePerLevel, GateFireAspect::fireAspectOf, () -> ThreadLocalRandom.current().nextDouble());
    }

    /** The lookups are injectable because Bukkit's enchantment constants need a running server. */
    GateFireAspect(double chancePerLevel, ToIntFunction<ItemStack> fireAspectLevel, DoubleSupplier roll) {
        this.chancePerLevel = Math.max(0.0, chancePerLevel);
        this.fireAspectLevel = fireAspectLevel;
        this.roll = roll;
    }

    /** Does this hit by {@code player} set the door block alight? Rolls once per call. */
    public boolean ignites(Player player) {
        if (player == null) {
            return false;
        }
        double chance = chance(fireAspectLevel.applyAsInt(player.getInventory().getItemInMainHand()));
        return chance > 0.0 && roll.getAsDouble() < chance;
    }

    /** The ignition chance of a weapon with Fire Aspect {@code level} (0 = none), capped at 1. */
    double chance(int level) {
        return level <= 0 ? 0.0 : Math.min(1.0, chancePerLevel * level);
    }

    private static int fireAspectOf(ItemStack item) {
        return item == null || item.isEmpty() ? 0 : item.getEnchantmentLevel(Enchantment.FIRE_ASPECT);
    }
}

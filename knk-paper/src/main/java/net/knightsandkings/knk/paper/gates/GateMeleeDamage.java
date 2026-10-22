package net.knightsandkings.knk.paper.gates;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;

/**
 * The damage a player's melee hit does to a gate door: what the held item would do to a player or mob.
 * <p>
 * That is the player's attack damage attribute (the base 1 plus the held item's modifiers - the "8 Attack
 * Damage" on a netherite sword's tooltip - plus potion effects such as the Strength a custom enchantment
 * grants) plus the Sharpness bonus, which vanilla adds separately when an entity is hit and which the
 * tooltip doesn't show: {@code 0.5 * level + 0.5}. Not included: critical hits, the swing cooldown (a click
 * on a block doesn't reset it) and Smite / Bane of Arthropods (a gate is neither undead nor an arthropod).
 * An empty hand therefore does 1.
 */
public class GateMeleeDamage {
    private final double multiplier;
    private final ToDoubleFunction<Player> attackDamage;
    private final ToIntFunction<ItemStack> sharpnessLevel;

    /** @param multiplier scales the item's damage for balance (1.0 = exactly what it does to a player) */
    public GateMeleeDamage(double multiplier) {
        this(multiplier, GateMeleeDamage::attackDamageOf, GateMeleeDamage::sharpnessOf);
    }

    /** The lookups are injectable because Bukkit's attribute and enchantment constants need a running server. */
    GateMeleeDamage(double multiplier, ToDoubleFunction<Player> attackDamage, ToIntFunction<ItemStack> sharpnessLevel) {
        this.multiplier = Math.max(0.0, multiplier);
        this.attackDamage = attackDamage;
        this.sharpnessLevel = sharpnessLevel;
    }

    /** The damage this player's melee hit does to a gate right now, or null for no player (the cause's default applies). */
    public Double damageOf(Player player) {
        if (player == null) {
            return null;
        }
        double perHit = attackDamage.applyAsDouble(player) + sharpnessBonus(sharpnessLevel.applyAsInt(player.getInventory().getItemInMainHand()));
        return Math.max(0.0, perHit) * multiplier;
    }

    /** Vanilla's Sharpness bonus: I = 1.0, then 0.5 more per level (V = 3.0). */
    static double sharpnessBonus(int level) {
        return level <= 0 ? 0.0 : 0.5 * level + 0.5;
    }

    private static double attackDamageOf(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.ATTACK_DAMAGE);
        return attribute == null ? 1.0 : attribute.getValue();
    }

    private static int sharpnessOf(ItemStack item) {
        return item == null || item.isEmpty() ? 0 : item.getEnchantmentLevel(Enchantment.SHARPNESS);
    }
}

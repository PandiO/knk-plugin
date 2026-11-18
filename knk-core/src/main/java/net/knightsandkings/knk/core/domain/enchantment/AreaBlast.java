package net.knightsandkings.knk.core.domain.enchantment;

/**
 * The area damage of a right-click blast (Chaos, Flash Chaos; PandiO/knk-workspace#223), at level I. The damage grows
 * per level with the enchantment's level multiplier ({@link EnchantmentTuning#scaleAt}); the radii don't.
 *
 * @param damage       damage to each living entity within {@code damageRadius} blocks
 * @param gateDamage   damage to each gate with a door block in reach (the reach is the gate side's
 *                     {@code gates.area-enchantment-radius-blocks}, not these radii)
 * @param damageRadius blocks from the user within which entities take {@code damage}
 * @param effectRadius blocks from the user within which entities are knocked back (Flash Chaos: and get its debuffs);
 *                     never below the damage radius in effect ({@link #reach})
 */
public record AreaBlast(double damage, double gateDamage, double damageRadius, double effectRadius) {

    /** No blast: what every enchantment but Chaos and Flash Chaos has. */
    public static final AreaBlast NONE = new AreaBlast(0.0d, 0.0d, 0.0d, 0.0d);

    public AreaBlast {
        damage = nonNegative(damage);
        gateDamage = nonNegative(gateDamage);
        damageRadius = nonNegative(damageRadius);
        effectRadius = nonNegative(effectRadius);
    }

    /** How far the blast reaches at all: the effect radius, or the damage radius when that is larger. */
    public double reach() {
        return Math.max(damageRadius, effectRadius);
    }

    private static double nonNegative(double value) {
        return Double.isFinite(value) ? Math.max(0.0d, value) : 0.0d;
    }
}

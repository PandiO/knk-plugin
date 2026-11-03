package net.knightsandkings.knk.core.npc.resolve;

/**
 * The live engine values an adapter supplies as bases for the damage stats a {@code CombatProfile}
 * leaves vanilla: the mob's current {@code ATTACK_DAMAGE} and the projectile's own damage (for an
 * arrow {@code power × 2 + difficulty term}, read in the shoot or damage event). The pipeline scales
 * and clamps them; it never invents a damage number.
 *
 * @param meleeDamage      the engine's melee damage for this actor (0 when unknown or not melee)
 * @param projectileDamage the engine's projectile damage for this shot (0 when unknown or not ranged)
 */
public record VanillaBases(double meleeDamage, double projectileDamage) {

    /** No live values (melee-only or test use): vanilla damages resolve from a base of 0. */
    public static final VanillaBases ZERO = new VanillaBases(0.0, 0.0);

    public VanillaBases {
        if (!Double.isFinite(meleeDamage) || !Double.isFinite(projectileDamage)) {
            throw new IllegalArgumentException("vanilla bases must be finite: " + meleeDamage + ", " + projectileDamage);
        }
    }

    public static VanillaBases melee(double meleeDamage) {
        return new VanillaBases(meleeDamage, 0.0);
    }

    public static VanillaBases projectile(double projectileDamage) {
        return new VanillaBases(0.0, projectileDamage);
    }
}

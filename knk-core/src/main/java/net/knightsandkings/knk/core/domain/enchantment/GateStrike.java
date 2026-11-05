package net.knightsandkings.knk.core.domain.enchantment;

/**
 * Cleaving's gate numbers (PandiO/knk-workspace#259): its melee bonus on gate doors and its charged right-click strike.
 * Level I plus a step per level (developer decision 2026-11-05); the strike's cooldown at level I is the enchantment's
 * own cooldown ({@link EnchantmentTuning#cooldownMs()}), with {@link #cooldownPerLevelSeconds} added per level.
 * Built-in: +25 % per level, strike 5x + 1 per level (5/6/7/8), cooldown 60 s - 10 s per level (60/50/40/30), the old
 * {@code gates.cleaving} defaults.
 *
 * @param damageBonusPerLevel     extra share of the weapon's gate damage per level: hits x (1 + bonus x level)
 * @param strikeMultiplier        the charged strike at level I, as a multiple of that boosted hit
 * @param strikeMultiplierPerLevel added to the strike multiplier per level above I (may be negative)
 * @param cooldownPerLevelSeconds  added to the strike's cooldown per level above I (negative = shorter)
 */
public record GateStrike(double damageBonusPerLevel, double strikeMultiplier, double strikeMultiplierPerLevel,
                         int cooldownPerLevelSeconds) {

    /** No gate strike: what every enchantment but Cleaving has. */
    public static final GateStrike NONE = new GateStrike(0.0d, 0.0d, 0.0d, 0);

    public GateStrike {
        damageBonusPerLevel = Double.isFinite(damageBonusPerLevel) ? Math.max(0.0d, damageBonusPerLevel) : 0.0d;
        strikeMultiplier = Double.isFinite(strikeMultiplier) ? Math.max(0.0d, strikeMultiplier) : 0.0d;
        strikeMultiplierPerLevel = Double.isFinite(strikeMultiplierPerLevel) ? strikeMultiplierPerLevel : 0.0d;
    }

    /** The factor a melee hit on a gate door is multiplied by at this level (1 below level I). */
    public double damageMultiplierAt(int level) {
        return level <= 0 ? 1.0d : 1.0d + damageBonusPerLevel * level;
    }

    /** The charged strike at this level as a multiple of the boosted melee hit, never below 0. */
    public double strikeMultiplierAt(int level) {
        return Math.max(0.0d, strikeMultiplier + strikeMultiplierPerLevel * Math.max(0, level - 1));
    }
}

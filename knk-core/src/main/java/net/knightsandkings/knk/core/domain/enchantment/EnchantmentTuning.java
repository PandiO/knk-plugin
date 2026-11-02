package net.knightsandkings.knk.core.domain.enchantment;

/**
 * The combat values of one custom enchantment (PandiO/knk-workspace#151): how often an on-hit effect goes off, the
 * cooldown of the player who set it off, how long the effect lasts and how that grows per level. Built-in values live
 * in {@link EnchantmentTuningTable}; an admin overrides them per enchantment in the web app
 * ({@link EnchantmentTuningOverride}).
 * <p>
 * At level {@code n}: chance = {@code procChance + procChancePerLevel x (n - 1)}, kept between 0 and 1; duration =
 * {@code durationTicks x (1 + levelMultiplier x (n - 1))}.
 *
 * @param procChance         on-hit chance at level I, 0-1 (1 = every hit); right-click abilities don't roll it
 * @param procChancePerLevel added to the chance per level above I (may be negative)
 * @param cooldownEnabled    whether a proc or activation starts a cooldown for the player who used the enchantment
 * @param cooldownMs         cooldown length, kept while the cooldown is switched off
 * @param durationTicks      effect duration at level I in ticks; 0 for abilities without a duration
 * @param levelMultiplier    share of the level-I duration added per level above I
 */
public record EnchantmentTuning(
        double procChance,
        double procChancePerLevel,
        boolean cooldownEnabled,
        long cooldownMs,
        int durationTicks,
        double levelMultiplier
) {
    /** No chance roll, no cooldown, no duration: what an enchantment without tuning gets. */
    public static final EnchantmentTuning NONE = new EnchantmentTuning(1.0d, 0.0d, false, 0L, 0, 0.0d);

    public EnchantmentTuning {
        procChance = finiteOr(procChance, 0.0d);
        procChancePerLevel = finiteOr(procChancePerLevel, 0.0d);
        cooldownMs = Math.max(0L, cooldownMs);
        durationTicks = Math.max(0, durationTicks);
        levelMultiplier = Math.max(0.0d, finiteOr(levelMultiplier, 0.0d));
    }

    /** The chance (0-1) that a hit at this level sets the effect off; 0 below level I. */
    public double chanceAt(int level) {
        if (level < 1) {
            return 0.0d;
        }
        double chance = procChance + procChancePerLevel * (level - 1);
        return Math.min(1.0d, Math.max(0.0d, chance));
    }

    /** The effect duration in ticks at this level (level I's below level I). */
    public int durationTicksAt(int level) {
        return (int) Math.round(durationTicks * scaleAt(level));
    }

    /** The factor a level-scaled value gets at this level: {@code 1 + levelMultiplier x (level - 1)}. */
    public double scaleAt(int level) {
        return 1.0d + levelMultiplier * Math.max(0, level - 1);
    }

    /** The cooldown a proc or activation starts, 0 when the cooldown is switched off. */
    public long effectiveCooldownMs() {
        return cooldownEnabled ? cooldownMs : 0L;
    }

    /** These values with every non-null value of the override in place of the built-in one. */
    public EnchantmentTuning with(EnchantmentTuningOverride override) {
        if (override == null) {
            return this;
        }
        return new EnchantmentTuning(
                override.procChancePercent() != null ? override.procChancePercent() / 100.0d : procChance,
                override.procChancePerLevelPercent() != null ? override.procChancePerLevelPercent() / 100.0d : procChancePerLevel,
                override.cooldownEnabled() != null ? override.cooldownEnabled() : cooldownEnabled,
                override.cooldownSeconds() != null ? override.cooldownSeconds() * 1000L : cooldownMs,
                override.effectDurationSeconds() != null ? (int) Math.round(override.effectDurationSeconds() * 20.0d) : durationTicks,
                override.levelMultiplier() != null ? override.levelMultiplier() : levelMultiplier
        );
    }

    private static double finiteOr(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }
}

package net.knightsandkings.knk.core.domain.enchantment;

/**
 * The combat values of one custom enchantment (PandiO/knk-workspace#151): how often an on-hit effect goes off, the
 * cooldown of the player who set it off, how long the effect lasts and how that grows per level. Built-in values live
 * in {@link EnchantmentTuningTable}; an admin overrides them per enchantment in the web app
 * ({@link EnchantmentTuningOverride}).
 * <p>
 * At level {@code n}: chance = {@code procChance + procChancePerLevel x (n - 1)}, kept between 0 and 1; duration =
 * {@code durationTicks x (1 + levelMultiplier x (n - 1))}; a blast's damage (#223) grows the same way.
 *
 * @param procChance         on-hit chance at level I, 0-1 (1 = every hit); right-click abilities don't roll it
 * @param procChancePerLevel added to the chance per level above I (may be negative)
 * @param cooldownEnabled    whether a proc or activation starts a cooldown for the player who used the enchantment
 * @param cooldownMs         cooldown length, kept while the cooldown is switched off
 * @param durationTicks      effect duration at level I in ticks; 0 for abilities without a duration
 * @param levelMultiplier    share of the level-I duration added per level above I (also of the second duration)
 * @param effectLevel        level of the effect's potion, 1 = level I; 0 when the enchantment has none. Flash Chaos:
 *                           its Slowness (#201)
 * @param secondaryDurationTicks duration at level I of a second effect in ticks; 0 when there is none. Flash Chaos:
 *                           its Nausea (#201)
 * @param area               the area damage and radii of a right-click blast at level I (Chaos, Flash Chaos; #223);
 *                           {@link AreaBlast#NONE} for the others. The damage grows per level like the durations
 * @param gateStrike         Cleaving's gate bonus and charged strike (#259); {@link GateStrike#NONE} for the others
 */
public record EnchantmentTuning(
        double procChance,
        double procChancePerLevel,
        boolean cooldownEnabled,
        long cooldownMs,
        int durationTicks,
        double levelMultiplier,
        int effectLevel,
        int secondaryDurationTicks,
        AreaBlast area,
        GateStrike gateStrike
) {
    /** Highest effect level, as in knk-web-api. */
    public static final int MAX_EFFECT_LEVEL = 10;

    /** No chance roll, no cooldown, no duration: what an enchantment without tuning gets. */
    public static final EnchantmentTuning NONE = new EnchantmentTuning(1.0d, 0.0d, false, 0L, 0, 0.0d);

    public EnchantmentTuning {
        procChance = finiteOr(procChance, 0.0d);
        procChancePerLevel = finiteOr(procChancePerLevel, 0.0d);
        cooldownMs = Math.max(0L, cooldownMs);
        durationTicks = Math.max(0, durationTicks);
        levelMultiplier = Math.max(0.0d, finiteOr(levelMultiplier, 0.0d));
        effectLevel = Math.max(0, Math.min(MAX_EFFECT_LEVEL, effectLevel));
        secondaryDurationTicks = Math.max(0, secondaryDurationTicks);
        area = area != null ? area : AreaBlast.NONE;
        gateStrike = gateStrike != null ? gateStrike : GateStrike.NONE;
    }

    /** An enchantment without a gate strike (every one but Cleaving). */
    public EnchantmentTuning(double procChance, double procChancePerLevel, boolean cooldownEnabled, long cooldownMs,
                             int durationTicks, double levelMultiplier, int effectLevel, int secondaryDurationTicks,
                             AreaBlast area) {
        this(procChance, procChancePerLevel, cooldownEnabled, cooldownMs, durationTicks, levelMultiplier, effectLevel,
                secondaryDurationTicks, area, GateStrike.NONE);
    }

    /** An enchantment without an area blast (every one but Chaos and Flash Chaos). */
    public EnchantmentTuning(double procChance, double procChancePerLevel, boolean cooldownEnabled, long cooldownMs,
                             int durationTicks, double levelMultiplier, int effectLevel, int secondaryDurationTicks) {
        this(procChance, procChancePerLevel, cooldownEnabled, cooldownMs, durationTicks, levelMultiplier, effectLevel,
                secondaryDurationTicks, AreaBlast.NONE);
    }

    /** An enchantment with one effect and no effect level (every one but Flash Chaos). */
    public EnchantmentTuning(double procChance, double procChancePerLevel, boolean cooldownEnabled, long cooldownMs,
                             int durationTicks, double levelMultiplier) {
        this(procChance, procChancePerLevel, cooldownEnabled, cooldownMs, durationTicks, levelMultiplier, 0, 0,
                AreaBlast.NONE);
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

    /** The second effect's duration in ticks at this level, scaled like {@link #durationTicksAt} (#201). */
    public int secondaryDurationTicksAt(int level) {
        return (int) Math.round(secondaryDurationTicks * scaleAt(level));
    }

    /** The area damage to each entity in the damage radius at this level, scaled like {@link #durationTicksAt} (#223). */
    public double areaDamageAt(int level) {
        return area.damage() * scaleAt(level);
    }

    /** The damage to each gate in reach at this level, scaled like {@link #areaDamageAt} (#223). */
    public double gateDamageAt(int level) {
        return area.gateDamage() * scaleAt(level);
    }

    /**
     * Cleaving's charged-strike cooldown at this level (#259): the cooldown plus {@code cooldownPerLevelSeconds} per level
     * above I, never below 0; 0 when the cooldown is switched off.
     */
    public long strikeCooldownMsAt(int level) {
        if (!cooldownEnabled) {
            return 0L;
        }
        return Math.max(0L, cooldownMs + gateStrike.cooldownPerLevelSeconds() * 1000L * Math.max(0, level - 1));
    }

    /**
     * The potion amplifier of the effect level ({@code effectLevel - 1}), never below 0: Slowness I = amplifier 0.
     */
    public int effectAmplifier() {
        return Math.max(0, effectLevel - 1);
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
                override.levelMultiplier() != null ? override.levelMultiplier() : levelMultiplier,
                override.effectLevel() != null ? override.effectLevel() : effectLevel,
                override.secondaryEffectDurationSeconds() != null
                        ? (int) Math.round(override.secondaryEffectDurationSeconds() * 20.0d) : secondaryDurationTicks,
                new AreaBlast(
                        override.areaDamage() != null ? override.areaDamage() : area.damage(),
                        override.gateDamage() != null ? override.gateDamage() : area.gateDamage(),
                        override.damageRadiusBlocks() != null ? override.damageRadiusBlocks() : area.damageRadius(),
                        override.effectRadiusBlocks() != null ? override.effectRadiusBlocks() : area.effectRadius()),
                new GateStrike(
                        override.gateDamageBonusPerLevel() != null ? override.gateDamageBonusPerLevel() : gateStrike.damageBonusPerLevel(),
                        override.strikeMultiplier() != null ? override.strikeMultiplier() : gateStrike.strikeMultiplier(),
                        override.strikeMultiplierPerLevel() != null ? override.strikeMultiplierPerLevel() : gateStrike.strikeMultiplierPerLevel(),
                        override.cooldownPerLevelSeconds() != null ? override.cooldownPerLevelSeconds() : gateStrike.cooldownPerLevelSeconds())
        );
    }

    private static double finiteOr(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }
}

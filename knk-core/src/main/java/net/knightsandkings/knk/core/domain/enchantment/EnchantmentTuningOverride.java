package net.knightsandkings.knk.core.domain.enchantment;

/**
 * The combat values an admin set on one enchantment definition in knk-web-api (PandiO/knk-workspace#151). Every value
 * may be null, meaning "keep the plugin's built-in value" ({@link EnchantmentTuningTable}).
 *
 * @param key                       the definition's key ({@code freeze}, {@code knk:freeze}, ...)
 * @param procChancePercent         on-hit chance at level I, 0-100
 * @param procChancePerLevelPercent percentage points added per level above I
 * @param cooldownEnabled           cooldown on/off
 * @param cooldownSeconds           cooldown length
 * @param effectDurationSeconds     effect duration at level I
 * @param levelMultiplier           share of the level-I duration added per level above I
 * @param effectLevel               level of the effect's potion, 1 = level I (Flash Chaos: Slowness, #201)
 * @param secondaryEffectDurationSeconds duration at level I of the second effect (Flash Chaos: Nausea, #201)
 */
public record EnchantmentTuningOverride(
        String key,
        Double procChancePercent,
        Double procChancePerLevelPercent,
        Boolean cooldownEnabled,
        Integer cooldownSeconds,
        Double effectDurationSeconds,
        Double levelMultiplier,
        Integer effectLevel,
        Double secondaryEffectDurationSeconds
) {
    /** The #151 values only, without an effect level or a second duration. */
    public EnchantmentTuningOverride(String key, Double procChancePercent, Double procChancePerLevelPercent,
                                     Boolean cooldownEnabled, Integer cooldownSeconds, Double effectDurationSeconds,
                                     Double levelMultiplier) {
        this(key, procChancePercent, procChancePerLevelPercent, cooldownEnabled, cooldownSeconds, effectDurationSeconds,
                levelMultiplier, null, null);
    }

    /** True when no value is set, so the built-in values apply unchanged. */
    public boolean isEmpty() {
        return procChancePercent == null && procChancePerLevelPercent == null && cooldownEnabled == null
                && cooldownSeconds == null && effectDurationSeconds == null && levelMultiplier == null
                && effectLevel == null && secondaryEffectDurationSeconds == null;
    }
}

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
 * @param areaDamage                area damage to entities at level I (Chaos, Flash Chaos; #223)
 * @param gateDamage                damage to gate doors in reach at level I (Chaos, Flash Chaos; #223)
 * @param damageRadiusBlocks        radius of the area damage (#223)
 * @param effectRadiusBlocks        radius of the knockback (Flash Chaos: and its debuffs; #223)
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
        Double secondaryEffectDurationSeconds,
        Double areaDamage,
        Double gateDamage,
        Double damageRadiusBlocks,
        Double effectRadiusBlocks
) {
    /** The #151 and #201 values, without the area blast (#223). */
    public EnchantmentTuningOverride(String key, Double procChancePercent, Double procChancePerLevelPercent,
                                     Boolean cooldownEnabled, Integer cooldownSeconds, Double effectDurationSeconds,
                                     Double levelMultiplier, Integer effectLevel, Double secondaryEffectDurationSeconds) {
        this(key, procChancePercent, procChancePerLevelPercent, cooldownEnabled, cooldownSeconds, effectDurationSeconds,
                levelMultiplier, effectLevel, secondaryEffectDurationSeconds, null, null, null, null);
    }

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
                && effectLevel == null && secondaryEffectDurationSeconds == null
                && areaDamage == null && gateDamage == null && damageRadiusBlocks == null && effectRadiusBlocks == null;
    }
}

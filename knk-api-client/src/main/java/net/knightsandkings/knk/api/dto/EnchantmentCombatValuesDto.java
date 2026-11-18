package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The fields of knk-web-api's {@code EnchantmentDefinitionReadDto} the plugin needs for the combat values of a custom
 * enchantment (PandiO/knk-workspace#151); everything else in the response is ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EnchantmentCombatValuesDto(
        @JsonProperty("key") String key,
        @JsonProperty("isCustom") Boolean isCustom,
        @JsonProperty("procChancePercent") Double procChancePercent,
        @JsonProperty("procChancePerLevelPercent") Double procChancePerLevelPercent,
        @JsonProperty("cooldownEnabled") Boolean cooldownEnabled,
        @JsonProperty("cooldownSeconds") Integer cooldownSeconds,
        @JsonProperty("effectDurationSeconds") Double effectDurationSeconds,
        @JsonProperty("levelMultiplier") Double levelMultiplier,
        // PandiO/knk-workspace#201: Flash Chaos's Slowness level and Nausea duration.
        @JsonProperty("effectLevel") Integer effectLevel,
        @JsonProperty("secondaryEffectDurationSeconds") Double secondaryEffectDurationSeconds,
        // PandiO/knk-workspace#223: Chaos / Flash Chaos area damage, gate damage and radii.
        @JsonProperty("areaDamage") Double areaDamage,
        @JsonProperty("gateDamage") Double gateDamage,
        @JsonProperty("damageRadiusBlocks") Double damageRadiusBlocks,
        @JsonProperty("effectRadiusBlocks") Double effectRadiusBlocks,
        // PandiO/knk-workspace#259: Cleaving's gate bonus and charged strike.
        @JsonProperty("gateDamageBonusPerLevel") Double gateDamageBonusPerLevel,
        @JsonProperty("strikeMultiplier") Double strikeMultiplier,
        @JsonProperty("strikeMultiplierPerLevel") Double strikeMultiplierPerLevel,
        @JsonProperty("cooldownPerLevelSeconds") Integer cooldownPerLevelSeconds
) {}

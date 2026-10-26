package net.knightsandkings.knk.core.domain.enchantment;

/**
 * A custom enchantment the plugin implements. Since PandiO/knk-workspace#151 combat does not read {@code cooldownMs}
 * and {@code triggerProbability} any more (they keep the original catalogue values): the live chance, cooldown,
 * duration and level scaling come from {@link EnchantmentTuningTable}, which admins override per enchantment.
 */
public record Enchantment(
        String id,
        String displayName,
        int maxLevel,
        int cooldownMs,
        EnchantmentType type,
        Double triggerProbability
) {}

package net.knightsandkings.knk.core.catalog;

import java.util.Objects;

/**
 * One enchantment of the Minecraft catalogue snapshot (KNG-250, knk-workspace
 * docs/specs/minecraft-catalog/IMPLEMENTATION_PLAN.md §12.2): a vanilla or data-pack enchantment from
 * the server's registry, or a custom one from knk-core's
 * {@link net.knightsandkings.knk.core.domain.enchantment.EnchantmentRegistry}.
 *
 * @param key            namespaced key, e.g. {@code minecraft:sharpness} or {@code knk:flash_chaos}
 * @param isCustom       true for a knk-core custom enchantment
 * @param maxLevel       the highest level, or null when unknown
 * @param translationKey the translation key of a registry enchantment; null for custom ones
 * @param displayName    the en_US name, or null when there is no translation
 * @param category       {@code ATTACK} or {@code SUPPORT} for custom enchantments; null for registry ones
 */
public record CatalogEnchantment(
        String key,
        boolean isCustom,
        Integer maxLevel,
        String translationKey,
        String displayName,
        String category
) {
    public CatalogEnchantment {
        Objects.requireNonNull(key, "key");
    }
}

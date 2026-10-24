package net.knightsandkings.knk.core.catalog;

import java.util.Objects;

/**
 * One entry of the Minecraft catalogue snapshot (KNG-200, knk-workspace
 * docs/specs/minecraft-catalog/IMPLEMENTATION_PLAN.md §5.2a): a material or block as the running
 * server reports it. Bukkit-free so the snapshot and its hash can be built and tested without a server.
 *
 * @param key               namespaced key, e.g. {@code minecraft:oak_stairs}
 * @param translationKey    item key for items, block key for block-only entries, or null
 * @param displayName       the translation key rendered to en_US, or null when there is no translation
 * @param defaultBlockState {@code BlockData.getAsString()} of the default state for blocks, else null
 */
public record CatalogEntry(
        String key,
        boolean isItem,
        boolean isBlock,
        int maxStackSize,
        String translationKey,
        String displayName,
        String defaultBlockState
) {
    public CatalogEntry {
        Objects.requireNonNull(key, "key");
    }
}

package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import net.knightsandkings.knk.core.catalog.CatalogEnchantment;

/**
 * One enchantment of {@link MinecraftCatalogSnapshotDto} (KNG-250, IMPLEMENTATION_PLAN.md §12.2); null
 * fields are left out of the JSON, as for the material entries.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MinecraftCatalogEnchantmentDto(
        @JsonProperty("key") String key,
        @JsonProperty("isCustom") boolean isCustom,
        @JsonProperty("maxLevel") Integer maxLevel,
        @JsonProperty("translationKey") String translationKey,
        @JsonProperty("displayName") String displayName,
        @JsonProperty("category") String category
) {
    public static MinecraftCatalogEnchantmentDto from(CatalogEnchantment enchantment) {
        return new MinecraftCatalogEnchantmentDto(enchantment.key(), enchantment.isCustom(), enchantment.maxLevel(),
            enchantment.translationKey(), enchantment.displayName(), enchantment.category());
    }
}

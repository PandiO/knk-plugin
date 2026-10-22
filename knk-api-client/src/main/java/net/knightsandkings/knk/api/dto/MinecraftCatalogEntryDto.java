package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import net.knightsandkings.knk.core.catalog.CatalogEntry;

/** One entry of {@link MinecraftCatalogSnapshotDto}; null fields are left out of the JSON. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MinecraftCatalogEntryDto(
        @JsonProperty("key") String key,
        @JsonProperty("isItem") boolean isItem,
        @JsonProperty("isBlock") boolean isBlock,
        @JsonProperty("maxStackSize") int maxStackSize,
        @JsonProperty("translationKey") String translationKey,
        @JsonProperty("displayName") String displayName,
        @JsonProperty("defaultBlockState") String defaultBlockState
) {
    public static MinecraftCatalogEntryDto from(CatalogEntry entry) {
        return new MinecraftCatalogEntryDto(entry.key(), entry.isItem(), entry.isBlock(), entry.maxStackSize(),
            entry.translationKey(), entry.displayName(), entry.defaultBlockState());
    }
}

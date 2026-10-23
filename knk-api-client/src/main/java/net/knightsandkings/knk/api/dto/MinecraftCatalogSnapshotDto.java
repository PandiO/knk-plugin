package net.knightsandkings.knk.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import net.knightsandkings.knk.core.catalog.CatalogSnapshot;

/**
 * Body of {@code PUT api/MinecraftCatalog/scans/{scanId}/snapshot} (KNG-200/KNG-250, knk-workspace
 * docs/specs/minecraft-catalog/IMPLEMENTATION_PLAN.md §5.2a and §12.2, schema version 2).
 */
public record MinecraftCatalogSnapshotDto(
        @JsonProperty("schemaVersion") int schemaVersion,
        @JsonProperty("minecraftVersion") String minecraftVersion,
        @JsonProperty("serverVersion") String serverVersion,
        @JsonProperty("contentHash") String contentHash,
        @JsonProperty("entries") List<MinecraftCatalogEntryDto> entries,
        @JsonProperty("enchantments") List<MinecraftCatalogEnchantmentDto> enchantments
) {
    public static MinecraftCatalogSnapshotDto from(CatalogSnapshot snapshot) {
        return new MinecraftCatalogSnapshotDto(snapshot.schemaVersion(), snapshot.minecraftVersion(),
            snapshot.serverVersion(), snapshot.contentHash(),
            snapshot.entries().stream().map(MinecraftCatalogEntryDto::from).toList(),
            snapshot.enchantments().stream().map(MinecraftCatalogEnchantmentDto::from).toList());
    }
}

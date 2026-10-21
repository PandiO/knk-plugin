package net.knightsandkings.knk.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import net.knightsandkings.knk.core.catalog.CatalogSnapshot;

/**
 * Body of {@code PUT api/MinecraftCatalog/scans/{scanId}/snapshot} (KNG-200, knk-workspace
 * docs/specs/minecraft-catalog/IMPLEMENTATION_PLAN.md §5.2a, schema version 1).
 */
public record MinecraftCatalogSnapshotDto(
        @JsonProperty("schemaVersion") int schemaVersion,
        @JsonProperty("minecraftVersion") String minecraftVersion,
        @JsonProperty("serverVersion") String serverVersion,
        @JsonProperty("contentHash") String contentHash,
        @JsonProperty("entries") List<MinecraftCatalogEntryDto> entries
) {
    public static MinecraftCatalogSnapshotDto from(CatalogSnapshot snapshot) {
        return new MinecraftCatalogSnapshotDto(snapshot.schemaVersion(), snapshot.minecraftVersion(),
            snapshot.serverVersion(), snapshot.contentHash(),
            snapshot.entries().stream().map(MinecraftCatalogEntryDto::from).toList());
    }
}

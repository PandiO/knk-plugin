package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record MenuTemplateDto(
        @JsonProperty("id") Integer id,
        @JsonProperty("key") String key,
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("height") Integer height,
        @JsonProperty("growth") String growth,
        @JsonProperty("backgroundMaterialRefId") Integer backgroundMaterialRefId,
        @JsonProperty("sections") List<MenuSectionTemplateDto> sections,
        // InventoryMenu Phase 9 (E4): re-render open instances every N ticks; null = off.
        @JsonProperty("autoRefreshTicks") Integer autoRefreshTicks
) {}

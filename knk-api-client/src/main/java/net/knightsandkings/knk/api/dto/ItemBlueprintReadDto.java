package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record ItemBlueprintReadDto(
        @JsonProperty("id") Integer id,
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("iconMaterialRefId") Integer iconMaterialRefId,
        @JsonProperty("iconMaterialRef") MinecraftMaterialRefNavDto iconMaterialRef,
        @JsonProperty("defaultDisplayName") String defaultDisplayName,
        @JsonProperty("defaultDisplayDescription") String defaultDisplayDescription,
        @JsonProperty("defaultQuantity") Integer defaultQuantity,
        @JsonProperty("maxStackSize") Integer maxStackSize,
        @JsonProperty("defaultEnchantments") List<ItemBlueprintDefaultEnchantmentDto> defaultEnchantments,
        @JsonProperty("grade") GradeDto grade,
        @JsonProperty("tags") List<ItemBlueprintTagDto> tags,
        @JsonProperty("origins") List<ItemBlueprintOriginDto> origins
) {}

package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ItemBlueprintListDto(
        @JsonProperty("id") Integer id,
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("defaultDisplayName") String defaultDisplayName,
        @JsonProperty("iconMaterialRefId") Integer iconMaterialRefId,
        @JsonProperty("iconNamespaceKey") String iconNamespaceKey,
        @JsonProperty("defaultEnchantmentsCount") Integer defaultEnchantmentsCount,
        @JsonProperty("gradeId") Integer gradeId,
        @JsonProperty("gradeName") String gradeName,
        @JsonProperty("tagsCount") Integer tagsCount
) {}

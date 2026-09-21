package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record MenuSectionTemplateDto(
        @JsonProperty("id") Integer id,
        @JsonProperty("name") String name,
        @JsonProperty("kind") String kind,
        @JsonProperty("sortOrder") Integer sortOrder,
        @JsonProperty("displaySlot") Integer displaySlot,
        @JsonProperty("width") Integer width,
        @JsonProperty("height") Integer height,
        @JsonProperty("positionMode") String positionMode,
        @JsonProperty("alignVertical") String alignVertical,
        @JsonProperty("alignHorizontal") String alignHorizontal,
        @JsonProperty("overflow") String overflow,
        @JsonProperty("listMode") String listMode,
        @JsonProperty("priority") String priority,
        @JsonProperty("visibilityPermission") String visibilityPermission,
        @JsonProperty("items") List<MenuItemTemplateDto> items,
        @JsonProperty("variableBindings") List<VariableBindingDto> variableBindings
) {}

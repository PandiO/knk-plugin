package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record MenuItemTemplateDto(
        @JsonProperty("id") Integer id,
        @JsonProperty("sortOrder") Integer sortOrder,
        @JsonProperty("slotOverride") Integer slotOverride,
        @JsonProperty("materialRefId") Integer materialRefId,
        @JsonProperty("amount") Integer amount,
        @JsonProperty("chatColorName") String chatColorName,
        @JsonProperty("chatColorDescription") String chatColorDescription,
        @JsonProperty("displayMode") String displayMode,
        @JsonProperty("visibilityPermission") String visibilityPermission,
        @JsonProperty("actionPermission") String actionPermission,
        @JsonProperty("variableBindings") List<VariableBindingDto> variableBindings,
        @JsonProperty("actions") List<ActionBindingDto> actions,
        @JsonProperty("conditions") List<ConditionBindingDto> conditions
) {}

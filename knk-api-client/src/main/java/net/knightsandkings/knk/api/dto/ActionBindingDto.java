package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record ActionBindingDto(
        @JsonProperty("id") Integer id,
        @JsonProperty("actionTypeId") String actionTypeId,
        @JsonProperty("paramsJson") String paramsJson,
        @JsonProperty("sortOrder") Integer sortOrder,
        @JsonProperty("conditions") List<ConditionBindingDto> conditions
) {}

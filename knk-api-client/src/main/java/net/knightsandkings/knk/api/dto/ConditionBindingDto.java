package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ConditionBindingDto(
        @JsonProperty("id") Integer id,
        @JsonProperty("conditionTypeId") String conditionTypeId,
        @JsonProperty("paramsJson") String paramsJson,
        @JsonProperty("sortOrder") Integer sortOrder
) {}

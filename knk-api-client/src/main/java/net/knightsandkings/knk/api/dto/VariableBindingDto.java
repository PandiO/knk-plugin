package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record VariableBindingDto(
        @JsonProperty("id") Integer id,
        @JsonProperty("targetProperty") String targetProperty,
        @JsonProperty("sortOrder") Integer sortOrder,
        @JsonProperty("expression") String expression,
        @JsonProperty("refreshPolicy") String refreshPolicy,
        @JsonProperty("ttlTicks") Integer ttlTicks
) {}

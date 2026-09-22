package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TagDto(
        @JsonProperty("id") Integer id,
        @JsonProperty("name") String name
) {}

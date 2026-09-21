package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MenuTemplateListDto(
        @JsonProperty("id") Integer id,
        @JsonProperty("key") String key,
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("sectionCount") Integer sectionCount
) {}

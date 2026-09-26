package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** The fields of knk-web-api's {@code CategoryDto} the plugin reads. */
public record CategoryListItemDto(
        @JsonProperty("id") Integer id,
        @JsonProperty("name") String name,
        @JsonProperty("parentCategoryId") Integer parentCategoryId
) {}

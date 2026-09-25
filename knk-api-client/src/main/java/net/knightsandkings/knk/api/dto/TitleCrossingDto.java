package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TitleCrossingDto(
    @JsonProperty("titleBracketId") int titleBracketId,
    @JsonProperty("titleName") String titleName
) {}

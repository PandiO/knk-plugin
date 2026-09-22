package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ItemBlueprintTagDto(
        @JsonProperty("itemBlueprintId") Integer itemBlueprintId,
        @JsonProperty("tagId") Integer tagId,
        @JsonProperty("tag") TagDto tag
) {}

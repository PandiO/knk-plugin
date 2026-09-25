package net.knightsandkings.knk.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TitleChangeResultDto(
    @JsonProperty("direction") String direction,
    @JsonProperty("fromTitleBracketId") int fromTitleBracketId,
    @JsonProperty("fromTitleName") String fromTitleName,
    @JsonProperty("toTitleBracketId") int toTitleBracketId,
    @JsonProperty("toTitleName") String toTitleName,
    @JsonProperty("crossedTitles") List<TitleCrossingDto> crossedTitles,
    @JsonProperty("coinBonusGranted") int coinBonusGranted,
    @JsonProperty("gemBonusGranted") int gemBonusGranted,
    @JsonProperty("expBonusGranted") int expBonusGranted
) {}

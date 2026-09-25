package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record BalanceAdjustmentResultDto(
    @JsonProperty("newCoins") int newCoins,
    @JsonProperty("newGems") int newGems,
    @JsonProperty("newExperiencePoints") int newExperiencePoints,
    @JsonProperty("titleChange") TitleChangeResultDto titleChange
) {}

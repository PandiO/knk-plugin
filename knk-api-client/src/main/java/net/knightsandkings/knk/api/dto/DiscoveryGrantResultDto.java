package net.knightsandkings.knk.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Maps to knk-web-api's DiscoveryGrantResultDto (domain discovery grant, KNG-20). */
public record DiscoveryGrantResultDto(
    @JsonProperty("granted") List<DiscoveryGrantDto> granted,
    @JsonProperty("alreadyDiscovered") List<Integer> alreadyDiscovered,
    @JsonProperty("skipped") List<DiscoverySkipDto> skipped,
    @JsonProperty("totalCoins") int totalCoins,
    @JsonProperty("totalGems") int totalGems,
    @JsonProperty("totalExp") int totalExp,
    @JsonProperty("totalCoinsBase") int totalCoinsBase,
    @JsonProperty("totalGemsBase") int totalGemsBase,
    @JsonProperty("totalExpBase") int totalExpBase,
    @JsonProperty("coinMultipliers") List<RewardMultiplierDto> coinMultipliers,
    @JsonProperty("gemMultipliers") List<RewardMultiplierDto> gemMultipliers,
    @JsonProperty("expMultipliers") List<RewardMultiplierDto> expMultipliers,
    @JsonProperty("titleBracketId") Integer titleBracketId,
    @JsonProperty("newCoins") int newCoins,
    @JsonProperty("newGems") int newGems,
    @JsonProperty("newExperiencePoints") int newExperiencePoints,
    @JsonProperty("titleChange") TitleChangeResultDto titleChange
) {}

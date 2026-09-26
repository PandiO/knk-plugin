package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** {@code GET /api/title-brackets} element (knk-web-api {@code TitleBracketDto}). */
public record TitleBracketDto(
        @JsonProperty("id") int id,
        @JsonProperty("maleName") String maleName,
        @JsonProperty("femaleName") String femaleName,
        @JsonProperty("minExperience") int minExperience,
        @JsonProperty("salary") int salary,
        @JsonProperty("coinBonus") int coinBonus,
        @JsonProperty("gemBonus") int gemBonus,
        @JsonProperty("expBonus") int expBonus
) {}

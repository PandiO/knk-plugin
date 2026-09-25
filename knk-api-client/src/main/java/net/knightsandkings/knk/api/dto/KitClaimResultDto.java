package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Mirrors knk-web-api's {@code KitClaimResultDto} field-for-field (DESIGN.md §4.1/§4.2). */
public record KitClaimResultDto(
        @JsonProperty("kitId") Integer kitId,
        @JsonProperty("helmetId") Integer helmetId,
        @JsonProperty("chestplateId") Integer chestplateId,
        @JsonProperty("leggingsId") Integer leggingsId,
        @JsonProperty("bootsId") Integer bootsId,
        @JsonProperty("shieldId") Integer shieldId,
        @JsonProperty("handId") Integer handId,
        @JsonProperty("contents") List<KitContentSlotDto> contents
) {}

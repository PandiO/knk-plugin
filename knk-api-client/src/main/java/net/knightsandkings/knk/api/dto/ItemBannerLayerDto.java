package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One banner pattern layer of a shield on an item blueprint, item instance or lootbox claim
 * (PandiO/knk-workspace#232); mirrors knk-web-api's {@code ItemBannerLayerDto}. Lists are bottom to top.
 */
public record ItemBannerLayerDto(
        @JsonProperty("patternKey") String patternKey,
        @JsonProperty("color") String color
) {}

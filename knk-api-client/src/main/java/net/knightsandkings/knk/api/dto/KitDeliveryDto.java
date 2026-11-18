package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Mirrors knk-web-api's {@code KitDeliveryDto}: a staff grant waiting for its in-game hand-over
 * (PandiO/knk-workspace#261), read from {@code GET api/Kits/deliveries/pending}.
 */
public record KitDeliveryDto(
        @JsonProperty("claimId") Integer claimId,
        @JsonProperty("kitId") Integer kitId,
        @JsonProperty("kitName") String kitName,
        @JsonProperty("loadout") KitClaimResultDto loadout
) {}

package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Mirrors knk-web-api's {@code KitAvailabilityDto} field-for-field (DESIGN.md §4.1). */
public record KitAvailabilityDto(
        @JsonProperty("kitId") Integer kitId,
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("canClaim") Boolean canClaim,
        @JsonProperty("denialReason") String denialReason,
        @JsonProperty("cooldownExpiresAt") java.time.OffsetDateTime cooldownExpiresAt,
        @JsonProperty("isPurchased") Boolean isPurchased,
        @JsonProperty("costAmount") Integer costAmount,
        @JsonProperty("costCurrency") String costCurrency,
        @JsonProperty("isSinglePurchasePremium") Boolean isSinglePurchasePremium,
        @JsonProperty("premiumPriceGems") Integer premiumPriceGems
) {}

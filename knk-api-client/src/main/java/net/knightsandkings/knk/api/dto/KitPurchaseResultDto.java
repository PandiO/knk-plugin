package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Mirrors knk-web-api's {@code KitPurchaseResultDto} field-for-field (DESIGN.md §4.1/§5.2). */
public record KitPurchaseResultDto(
        @JsonProperty("kitId") Integer kitId,
        @JsonProperty("userId") Integer userId,
        @JsonProperty("gemsPaid") Integer gemsPaid,
        @JsonProperty("purchasedAt") java.time.OffsetDateTime purchasedAt
) {}

package net.knightsandkings.knk.core.domain.item;

import java.time.OffsetDateTime;

/**
 * Per-kit availability summary for a given user (docs/specs/kits/DESIGN.md §4.1's
 * {@code GetAvailableForUserAsync}) - what {@code /kit list} renders from. Mirrors
 * knk-web-api's {@code KitAvailabilityDto} field-for-field.
 */
public record KnkKitAvailability(
        int kitId,
        String name,
        String description,
        boolean canClaim,
        String denialReason,
        OffsetDateTime cooldownExpiresAt,
        boolean isPurchased,
        Integer costAmount,
        String costCurrency,
        boolean isSinglePurchasePremium,
        Integer premiumPriceGems
) {}

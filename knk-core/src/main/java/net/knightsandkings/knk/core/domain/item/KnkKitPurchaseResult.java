package net.knightsandkings.knk.core.domain.item;

import java.time.OffsetDateTime;

/**
 * Result of a successful single-purchase-premium buy (docs/specs/kits/DESIGN.md §4.1/§5.2) -
 * purchasing and claiming are separate calls, so this deliberately carries no resolved loadout.
 * Mirrors knk-web-api's {@code KitPurchaseResultDto} field-for-field.
 */
public record KnkKitPurchaseResult(int kitId, int userId, int gemsPaid, OffsetDateTime purchasedAt) {}

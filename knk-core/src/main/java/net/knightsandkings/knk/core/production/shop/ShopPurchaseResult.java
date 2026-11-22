package net.knightsandkings.knk.core.production.shop;

/**
 * A purchase the API booked ({@code ShopPurchaseResultDto}).
 *
 * @param coinsAfter the player's coins after the purchase, when the API sent them
 * @param replayed   true for a retry with the same key (nothing booked twice)
 */
public record ShopPurchaseResult(int shopId, int itemBlueprintId, Long itemInstanceId, long amount, long unitPrice,
                                 long totalPrice, String deliverTo, Long itemDeliveryId, Long coinsAfter, boolean replayed) {
}

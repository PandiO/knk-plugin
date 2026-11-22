package net.knightsandkings.knk.core.production.shop;

/**
 * A quote or purchase ({@code ShopPurchaseRequestDto}). A quote sends only the player, the item and the amount.
 *
 * @param expectedUnitPrice the price the player saw (a purchase at another price is 409 {@code PriceChanged})
 * @param idempotencyKey    one per confirmation, reused for its retry ({@link ShopKeys})
 */
public record ShopPurchaseRequest(int userId, ShopItemRef item, long amount, Long expectedUnitPrice, boolean confirmed,
                                  DeliverTo deliverTo, String idempotencyKey) {

    /** Where the bought item goes. */
    public enum DeliverTo {
        /** The game server places it, after it checked the room. */
        INVENTORY("Inventory"),
        /** The P7 reward inbox; needs {@code Production:Enabled}. */
        INBOX("Inbox");

        private final String apiName;

        DeliverTo(String apiName) {
            this.apiName = apiName;
        }

        public String apiName() {
            return apiName;
        }
    }

    public static ShopPurchaseRequest quote(int userId, ShopItemRef item, long amount) {
        return new ShopPurchaseRequest(userId, item, amount, null, false, null, null);
    }

    public static ShopPurchaseRequest purchase(int userId, ShopItemRef item, long amount, long expectedUnitPrice,
                                               DeliverTo deliverTo, String idempotencyKey) {
        return new ShopPurchaseRequest(userId, item, amount, expectedUnitPrice, true, deliverTo, idempotencyKey);
    }
}

package net.knightsandkings.knk.core.production.shop;

/**
 * A purchase quote ({@code POST api/Shops/{id}/quote}; changes nothing).
 *
 * @param refusal null when it can be bought; else {@code NotForSale}, {@code SoldOut}, {@code NotEnoughStock} or
 *                {@code NoTown}
 */
public record ShopQuote(int shopId, Integer catalogueEntryId, int itemBlueprintId, String itemBlueprintName,
                        Long itemInstanceId, long amount, long unitPrice, long totalPrice, String priceSource, Long stock,
                        String refusal, String refusalMessage) {

    public boolean buyable() {
        return refusal == null || refusal.isBlank();
    }
}

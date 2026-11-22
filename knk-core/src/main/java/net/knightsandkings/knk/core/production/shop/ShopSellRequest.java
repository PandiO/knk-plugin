package net.knightsandkings.knk.core.production.shop;

import java.util.List;

/**
 * A sell quote or sale ({@code ShopSellRequestDto}). A quote sends only the player and the lines.
 *
 * @param expectedTotal the total the player saw (another total is 409 {@code PriceChanged})
 */
public record ShopSellRequest(int userId, List<ShopSellLine> lines, Long expectedTotal, boolean confirmed,
                              String idempotencyKey) {

    public ShopSellRequest {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public static ShopSellRequest quote(int userId, List<ShopSellLine> lines) {
        return new ShopSellRequest(userId, lines, null, false, null);
    }

    public static ShopSellRequest sell(int userId, List<ShopSellLine> lines, long expectedTotal, String idempotencyKey) {
        return new ShopSellRequest(userId, lines, expectedTotal, true, idempotencyKey);
    }
}

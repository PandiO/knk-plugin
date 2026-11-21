package net.knightsandkings.knk.core.production.shop;

import java.util.List;

/** A sale the API booked ({@code ShopSellResultDto}). */
public record ShopSellResult(int shopId, long total, List<ShopSellQuote.Line> lines, Long coinsAfter, boolean replayed) {

    public ShopSellResult {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}

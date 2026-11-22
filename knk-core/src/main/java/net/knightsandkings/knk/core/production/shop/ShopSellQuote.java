package net.knightsandkings.knk.core.production.shop;

import java.util.List;

/**
 * The buy-back prices of a basket ({@code POST api/Shops/{id}/sell-quote}, {@code ShopSellQuoteDto}); also the lines of a
 * booked sale.
 *
 * @param total      the accepted lines' total
 * @param accepted   every line is accepted (a sale is all lines or none)
 * @param townCanPay the town's account can pay the total
 */
public record ShopSellQuote(int shopId, List<Line> lines, long total, boolean accepted, boolean townCanPay) {

    public ShopSellQuote {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /**
     * @param refusal null when accepted; else {@code CategoryNotAccepted}, {@code NoPrice}, {@code NotResolved},
     *                {@code InStorage}, {@code Soulbound} or {@code NoRoom}
     */
    public record Line(int lineNo, Integer itemBlueprintId, String itemBlueprintName, Long itemInstanceId, long amount,
                       long unitPrice, long linePrice, String refusal, String refusalMessage) {

        public boolean accepted() {
            return refusal == null || refusal.isBlank();
        }
    }

    public Line line(int lineNo) {
        for (Line line : lines) {
            if (line.lineNo() == lineNo) {
                return line;
            }
        }
        return null;
    }
}

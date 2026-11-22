package net.knightsandkings.knk.core.production.shop.display;

/**
 * {@code production.shops.displays.*} (display stalls P12, PandiO/knk-workspace#250). Out-of-range values are clamped.
 *
 * @param enabled                 {@code production.enabled} (A21): off, no spot is shown and nothing is read
 * @param refreshMinutes          how often the registered spots are re-read (a spot added in the web app shows after this)
 * @param selectionRefreshSeconds how often the shown items of the stalls in loaded chunks are chosen again (offer, stock,
 *                                best sellers)
 * @param bestSellerHours         the trade stats window of the best sellers
 * @param itemScale               the size of the shown item (1 = a dropped item's size × 2)
 * @param label                   the floating name and price label
 * @param hints                   the label's line with the two clicks
 */
public record DisplaySettings(boolean enabled, int refreshMinutes, int selectionRefreshSeconds, int bestSellerHours,
                              double itemScale, boolean label, boolean hints) {

    public static final int DEFAULT_REFRESH_MINUTES = 5;
    public static final int DEFAULT_SELECTION_REFRESH_SECONDS = 30;
    public static final int DEFAULT_BEST_SELLER_HOURS = 168;
    public static final double DEFAULT_ITEM_SCALE = 0.5;

    public DisplaySettings {
        refreshMinutes = Math.clamp(refreshMinutes, 1, 1440);
        selectionRefreshSeconds = Math.clamp(selectionRefreshSeconds, 5, 3600);
        bestSellerHours = Math.clamp(bestSellerHours, 1, 24 * 90);
        itemScale = Double.isFinite(itemScale) ? Math.clamp(itemScale, 0.1, 2.0) : DEFAULT_ITEM_SCALE;
    }

    public static DisplaySettings defaults(boolean enabled) {
        return new DisplaySettings(enabled, DEFAULT_REFRESH_MINUTES, DEFAULT_SELECTION_REFRESH_SECONDS, DEFAULT_BEST_SELLER_HOURS,
                DEFAULT_ITEM_SCALE, true, true);
    }
}

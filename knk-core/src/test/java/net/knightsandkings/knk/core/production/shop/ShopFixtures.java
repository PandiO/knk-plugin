package net.knightsandkings.knk.core.production.shop;

import java.util.List;

/** Offer items for the shop menu tests (PandiO/knk-workspace#687). */
final class ShopFixtures {

    private ShopFixtures() {
    }

    static ShopOfferItem bread() {
        return new ShopOfferItem(12, 11, "Bread", null, 4, "Static", null, false, true, 0, List.of());
    }

    static ShopOfferItem flour(long stock) {
        return new ShopOfferItem(null, 15, "Flour", null, 3, "Formula", stock, stock == 0, false, Integer.MAX_VALUE, List.of());
    }

    static ShopOfferItem sword() {
        return new ShopOfferItem(null, 20, "Iron sword", 900L, 150, "Formula", 1L, false, false, Integer.MAX_VALUE,
                List.of("Sharpness 2"));
    }

    static ShopOffer offer(ShopOfferItem... items) {
        return new ShopOffer(5, "Bakery", "Processing", 2, "Bakery", 3, "Aldor", false, true, List.of(items));
    }
}

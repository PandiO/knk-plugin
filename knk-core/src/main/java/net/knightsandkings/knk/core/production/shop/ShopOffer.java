package net.knightsandkings.knk.core.production.shop;

import java.util.List;

/**
 * What a shop has on offer ({@code GET api/Shops/{id}/offer}, PandiO/knk-workspace#687; shops DESIGN § 8): the shop menu's
 * info header and its catalogue, in the API's order (pinned entries first, then sort order).
 *
 * @param buysFromPlayers the API's {@code Production:Enabled} is on and the shop type accepts categories (the sell menu)
 */
public record ShopOffer(int shopId, String shopName, String kind, int level, String shopTypeName, Integer townId,
                        String townName, boolean hideSoldOut, boolean buysFromPlayers, List<ShopOfferItem> items) {

    public ShopOffer {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** The item with {@code ref}, or null when the shop no longer offers it. */
    public ShopOfferItem find(ShopItemRef ref) {
        if (ref == null) {
            return null;
        }
        for (ShopOfferItem item : items) {
            if (ref.matches(item)) {
                return item;
            }
        }
        return null;
    }
}

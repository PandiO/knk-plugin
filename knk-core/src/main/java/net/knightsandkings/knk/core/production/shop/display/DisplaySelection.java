package net.knightsandkings.knk.core.production.shop.display;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.knightsandkings.knk.core.production.shop.ShopItemRef;
import net.knightsandkings.knk.core.production.shop.ShopOffer;
import net.knightsandkings.knk.core.production.shop.ShopOfferItem;

/**
 * What each display spot of a shop shows (P12, PandiO/knk-workspace#250, shops DESIGN § 7 "Selection"). Not every shop
 * can show its whole catalogue, so the spots are filled in their order ({@link DisplaySpotIndex#ORDER}):
 * <ol>
 *   <li>a spot's own pin ({@link DisplaySpot#pinnedCatalogueEntryId()}) shows that entry;</li>
 *   <li>the catalogue's pinned entries ({@link ShopOfferItem#pinned()}), in the offer's order, fill the next spots;</li>
 *   <li>the system fills the rest: in-stock items before sold-out ones, then best sellers (units sold in the trade stats'
 *       lookback window), then the offer's order;</li>
 *   <li>sold-out items are greyed out, or not shown at all when the shop hides them ({@link ShopOffer#hideSoldOut()}).</li>
 * </ol>
 * An item is shown on one spot at most. A disabled spot, and a spot left over when the offer runs out, shows nothing.
 * Pure: no Bukkit, no I/O.
 */
public final class DisplaySelection {

    /** What one spot shows. */
    public record Shown(DisplaySpot spot, ShopOfferItem item, boolean greyed) {

        public ShopItemRef ref() {
            return item.ref();
        }
    }

    private DisplaySelection() {
    }

    /**
     * @param spots     the shop's spots (any order; disabled ones show nothing)
     * @param offer     the shop's offer, or null (nothing is shown)
     * @param unitsSold units sold per blueprint id over the lookback window (best sellers); missing ids count 0
     * @return per spot id, what it shows; spots that show nothing are absent
     */
    public static Map<Integer, Shown> select(List<DisplaySpot> spots, ShopOffer offer, Map<Integer, Long> unitsSold) {
        Map<Integer, Shown> shown = new LinkedHashMap<>();
        if (spots == null || spots.isEmpty() || offer == null) {
            return shown;
        }
        List<DisplaySpot> open = new ArrayList<>();
        for (DisplaySpot spot : spots) {
            if (spot.enabled()) {
                open.add(spot);
            }
        }
        open.sort(DisplaySpotIndex.ORDER);

        List<ShopOfferItem> visible = new ArrayList<>();
        for (ShopOfferItem item : offer.items()) {
            if (!(item.soldOut() && offer.hideSoldOut())) {
                visible.add(item);
            }
        }
        Set<ShopItemRef> used = new HashSet<>();

        // 1. A spot's own pin.
        List<DisplaySpot> free = new ArrayList<>();
        for (DisplaySpot spot : open) {
            ShopOfferItem pinned = spot.pinnedCatalogueEntryId() == null ? null : entry(visible, spot.pinnedCatalogueEntryId());
            if (pinned != null && used.add(pinned.ref())) {
                shown.put(spot.id(), new Shown(spot, pinned, pinned.soldOut()));
            } else {
                free.add(spot); // no pin, or the pinned entry is gone, hidden or already shown: the spot is filled below
            }
        }

        // 2. and 3. The catalogue's pins, then the system's order.
        List<ShopOfferItem> candidates = new ArrayList<>();
        List<ShopOfferItem> rest = new ArrayList<>();
        for (ShopOfferItem item : visible) {
            if (used.contains(item.ref())) {
                continue;
            }
            (item.pinned() ? candidates : rest).add(item);
        }
        Map<ShopOfferItem, Integer> offerOrder = new LinkedHashMap<>();
        for (int i = 0; i < visible.size(); i++) {
            offerOrder.putIfAbsent(visible.get(i), i);
        }
        rest.sort(Comparator.comparing(ShopOfferItem::soldOut)
                .thenComparing(item -> -sold(unitsSold, item))
                .thenComparing(item -> offerOrder.getOrDefault(item, Integer.MAX_VALUE)));
        candidates.addAll(rest);

        int next = 0;
        for (DisplaySpot spot : free) {
            while (next < candidates.size() && used.contains(candidates.get(next).ref())) {
                next++;
            }
            if (next >= candidates.size()) {
                break;
            }
            ShopOfferItem item = candidates.get(next++);
            used.add(item.ref());
            shown.put(spot.id(), new Shown(spot, item, item.soldOut()));
        }
        return shown;
    }

    private static ShopOfferItem entry(List<ShopOfferItem> items, int catalogueEntryId) {
        for (ShopOfferItem item : items) {
            if (item.catalogueEntryId() != null && item.catalogueEntryId() == catalogueEntryId) {
                return item;
            }
        }
        return null;
    }

    private static long sold(Map<Integer, Long> unitsSold, ShopOfferItem item) {
        if (unitsSold == null) {
            return 0L;
        }
        Long units = unitsSold.get(item.itemBlueprintId());
        return units == null ? 0L : units;
    }
}

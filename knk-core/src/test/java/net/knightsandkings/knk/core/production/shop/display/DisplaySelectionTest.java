package net.knightsandkings.knk.core.production.shop.display;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.production.shop.ShopOffer;
import net.knightsandkings.knk.core.production.shop.ShopOfferItem;

/**
 * Display stalls P12 (PandiO/knk-workspace#250, shops DESIGN § 7 "Selection"): admin pins first (the spot's own, then the
 * catalogue's), then in-stock items and best sellers; sold-out items greyed out or hidden; one item per spot.
 */
class DisplaySelectionTest {

    // entry id, blueprint id, name, stock (null unlimited), pinned, sort order
    private static ShopOfferItem item(Integer entry, int blueprint, String name, Long stock, boolean pinned, int order) {
        return new ShopOfferItem(entry, blueprint, name, null, 5, "Static", stock, stock != null && stock == 0, pinned, order, List.of());
    }

    private static final ShopOfferItem BREAD = item(1, 11, "Bread", null, false, 1);
    private static final ShopOfferItem CAKE = item(2, 12, "Cake", 4L, true, 2);
    private static final ShopOfferItem PIE = item(3, 13, "Pie", 0L, false, 3);
    private static final ShopOfferItem ROLL = item(4, 14, "Roll", 9L, false, 4);
    private static final ShopOfferItem FLOUR = item(null, 15, "Flour", 30L, false, Integer.MAX_VALUE);

    private static ShopOffer offer(boolean hideSoldOut, ShopOfferItem... items) {
        return new ShopOffer(5, "Bakery", "Trading", 1, "Bakery", 1, "Aldor", hideSoldOut, true, List.of(items));
    }

    private static DisplaySpot spot(int id, int order, Integer pin) {
        return new DisplaySpot(id, 5, "world", id, 64, 0, DisplaySpot.Facing.SOUTH, pin, order, null, true);
    }

    private static List<String> names(Map<Integer, DisplaySelection.Shown> shown, List<DisplaySpot> spots) {
        List<String> out = new ArrayList<>();
        for (DisplaySpot s : spots.stream().sorted(DisplaySpotIndex.ORDER).toList()) {
            DisplaySelection.Shown x = shown.get(s.id());
            out.add(x == null ? "-" : x.item().itemBlueprintName() + (x.greyed() ? "*" : ""));
        }
        return out;
    }

    @Test
    void pinsComeFirst_thenInStockBestSellers_thenSoldOutGreyed() {
        List<DisplaySpot> spots = List.of(spot(1, 1, null), spot(2, 2, null), spot(3, 3, null), spot(4, 4, null), spot(5, 5, null));
        Map<Integer, Long> sold = Map.of(14, 50L, 11, 10L); // Roll sells best, then Bread
        Map<Integer, DisplaySelection.Shown> shown = DisplaySelection.select(spots, offer(false, BREAD, CAKE, PIE, ROLL, FLOUR), sold);
        assertEquals(List.of("Cake", "Roll", "Bread", "Flour", "Pie*"), names(shown, spots));
    }

    @Test
    void aSpotsOwnPin_winsOverTheCatalogue_andIsNotShownTwice() {
        List<DisplaySpot> spots = List.of(spot(1, 1, null), spot(2, 2, 3), spot(3, 3, null));
        Map<Integer, DisplaySelection.Shown> shown = DisplaySelection.select(spots, offer(false, BREAD, CAKE, PIE), Map.of());
        assertEquals(List.of("Cake", "Pie*", "Bread"), names(shown, spots));
        assertTrue(shown.get(2).greyed());
    }

    @Test
    void hiddenSoldOutItems_areNotShown_andAHiddenPinFallsBackToTheShop() {
        List<DisplaySpot> spots = List.of(spot(1, 1, 3), spot(2, 2, null), spot(3, 3, null), spot(4, 4, null));
        Map<Integer, DisplaySelection.Shown> shown = DisplaySelection.select(spots, offer(true, BREAD, CAKE, PIE), Map.of());
        assertEquals(List.of("Cake", "Bread", "-", "-"), names(shown, spots));
    }

    @Test
    void spotsFillInTheirOrder_disabledSpotsShowNothing_andExtraSpotsStayEmpty() {
        DisplaySpot off = new DisplaySpot(9, 5, "world", 9, 64, 0, DisplaySpot.Facing.SOUTH, null, 0, null, false);
        List<DisplaySpot> spots = List.of(spot(7, 20, null), off, spot(8, 10, null), spot(6, 30, null));
        Map<Integer, DisplaySelection.Shown> shown = DisplaySelection.select(spots, offer(false, BREAD, ROLL), Map.of());
        assertFalse(shown.containsKey(9));
        assertEquals("Bread", shown.get(8).item().itemBlueprintName()); // sort order 10 first
        assertEquals("Roll", shown.get(7).item().itemBlueprintName());
        assertFalse(shown.containsKey(6));
    }

    @Test
    void withoutBestSellers_theOffersOrderDecides_andNoOfferShowsNothing() {
        List<DisplaySpot> spots = List.of(spot(1, 1, null), spot(2, 2, null));
        assertEquals(List.of("Bread", "Roll"), names(DisplaySelection.select(spots, offer(false, PIE, BREAD, ROLL), null), spots));
        assertTrue(DisplaySelection.select(spots, null, Map.of()).isEmpty());
        assertTrue(DisplaySelection.select(List.of(), offer(false, BREAD), Map.of()).isEmpty());
    }

    @Test
    void aPinToAnEntryNoLongerOffered_isFilledByTheShop() {
        List<DisplaySpot> spots = List.of(spot(1, 1, 99));
        Map<Integer, DisplaySelection.Shown> shown = DisplaySelection.select(spots, offer(false, BREAD), Map.of());
        assertEquals("Bread", shown.get(1).item().itemBlueprintName());
        assertEquals("e1", shown.get(1).ref().toString());
    }
}

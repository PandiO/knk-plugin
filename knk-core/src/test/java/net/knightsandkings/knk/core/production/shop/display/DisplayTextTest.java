package net.knightsandkings.knk.core.production.shop.display;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.production.shop.ShopOfferItem;

/** Display stalls P12 (PandiO/knk-workspace#250): the floating label and the settings' bounds. */
class DisplayTextTest {

    private static final ShopOfferItem BREAD = new ShopOfferItem(12, 11, "Bread", null, 1200, "Static", null, false, false, 0, List.of());

    @Test
    void theLabelShowsNamePriceAndTheTwoClicks() {
        assertEquals(List.of("&fBread", "&61,200 coins &7each", "&8Right-click: information · Shift: buy"),
                DisplayText.label(BREAD, false, true));
        assertEquals(List.of("&7Bread", "&cSold out"), DisplayText.label(BREAD, true, false));
        assertEquals("&fBread\n&61,200 coins &7each", DisplayText.joined(DisplayText.label(BREAD, false, false)));
    }

    @Test
    void settingsAreClamped() {
        DisplaySettings s = new DisplaySettings(true, 0, 1, 0, 9.0, true, false);
        assertEquals(1, s.refreshMinutes());
        assertEquals(5, s.selectionRefreshSeconds());
        assertEquals(1, s.bestSellerHours());
        assertEquals(2.0, s.itemScale());
        assertEquals(0.5, new DisplaySettings(true, 5, 30, 168, Double.NaN, true, true).itemScale());
        assertEquals(168, DisplaySettings.defaults(false).bestSellerHours());
    }
}

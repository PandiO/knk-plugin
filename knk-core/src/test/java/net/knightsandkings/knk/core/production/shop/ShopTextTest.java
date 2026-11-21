package net.knightsandkings.knk.core.production.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** PandiO/knk-workspace#687: the lines of the shop menus, after the legacy V1 shop menus. */
class ShopTextTest {

    @Test
    void coins_formats() {
        assertEquals("1 coin", ShopText.coins(1));
        assertEquals("1,234 coins", ShopText.coins(1234));
        assertEquals("&7Your coins: &6500 coins", ShopText.coinsLine(500L));
        assertTrue(ShopText.coinsLine(null).contains("loading"));
    }

    @Test
    void shopLines_showTypeKindLevelTownAndSelling() {
        List<String> lines = ShopText.shopLines(ShopFixtures.offer());
        assertEquals(List.of("&7Type: &fBakery", "&7Kind: &fProcessing (makes its own goods)", "&7Level: &f2",
                "&7Town: &fAldor", "&aBuys items from players"), lines);
        assertEquals(List.of("&8Loading the shop..."), ShopText.shopLines(null));
    }

    @Test
    void rowLines_followTheLegacyPriceStockAndClickLines() {
        assertEquals(List.of("&7Price: &64 coins &7each", "&7Stock: &aplenty", "&eFeatured",
                "&eClick here for more information"), ShopText.rowLines(ShopFixtures.bread()));
        List<String> sold = ShopText.rowLines(ShopFixtures.flour(0));
        assertEquals("&7Stock: &c0", sold.get(1));
        assertEquals("&cSold out", sold.get(sold.size() - 1));
        List<String> sword = ShopText.rowLines(ShopFixtures.sword());
        assertTrue(sword.contains("&7Enchantments: &fSharpness 2"));
        assertTrue(sword.contains("&dUnique item"));
    }

    @Test
    void priceLines_sayWhatAClickDoes() {
        List<String> ok = ShopText.priceLines(ShopFixtures.bread(), 8, 100L);
        assertTrue(ok.contains("&7Total: &632 coins"));
        assertEquals("&aClick here to buy", ok.get(ok.size() - 1));
        List<String> poor = ShopText.priceLines(ShopFixtures.bread(), 8, 30L);
        assertEquals("&cYou need 2 coins more", poor.get(poor.size() - 1));
        List<String> out = ShopText.priceLines(ShopFixtures.flour(0), 1, 100L);
        assertEquals("&cOut of stock", out.get(out.size() - 1));
        List<String> unique = ShopText.priceLines(ShopFixtures.sword(), 1, 1000L);
        assertTrue(unique.get(unique.size() - 1).contains("#897"));
        assertEquals(List.of("&cThis item is no longer for sale here."), ShopText.priceLines(null, 1, 0L));
    }

    @Test
    void confirmLines_showThePriceAndAChangedPrice() {
        ShopQuote quote = new ShopQuote(5, 12, 11, "Bread", null, 8, 5, 40, "Static", null, null, null);
        List<String> lines = ShopText.confirmLines("Bread", 8, quote, 4L, 100L);
        assertEquals(List.of("&7Are you sure you want to buy", "&f8 x Bread&7?", "&7Price: &65 coins &7each",
                "&7Total: &640 coins", "&eThe price changed from 4 coins. Confirm again to buy."), lines);
        ShopQuote refused = new ShopQuote(5, 12, 11, "Bread", null, 8, 5, 40, "Static", 3L, "NotEnoughStock", null);
        List<String> no = ShopText.confirmLines("Bread", 8, refused, null, 100L);
        assertEquals("&c" + ShopRefusal.NOT_ENOUGH_STOCK.message(), no.get(no.size() - 1));
        assertTrue(ShopText.confirmLines("Bread", 8, null, null, 100L).get(0).contains("Asking"));
    }

    @Test
    void sellLines_showPricesRefusalsAndTheTotal() {
        ShopSellQuote.Line ok = new ShopSellQuote.Line(0, 11, "Bread", null, 74, 2, 148, null, null);
        assertEquals(List.of("&7Buy-back: &62 coins &7each", "&7For these 10: &620 coins", "&8Click to take it out"),
                ShopText.sellLineLines(ok, 10));
        ShopSellQuote.Line no = new ShopSellQuote.Line(1, 20, "Sword", 900L, 1, 0, 0, "CategoryNotAccepted", "x");
        assertEquals("&c" + ShopRefusal.CATEGORY_NOT_ACCEPTED.message(), ShopText.sellLineLines(no, 1).get(0));

        ShopSellQuote quote = new ShopSellQuote(5, List.of(ok), 148, true, true);
        assertEquals(List.of("&7Price: &6148 coins", "&aClick here to sell"), ShopText.sellTotalLines(quote, 1, false));
        assertEquals("&cTake out the marked items to sell", ShopText.sellTotalLines(
                new ShopSellQuote(5, List.of(ok, no), 148, false, true), 2, false).get(1));
        assertEquals("&cThe town can't pay this much right now", ShopText.sellTotalLines(
                new ShopSellQuote(5, List.of(ok), 148, true, false), 1, false).get(1));
        assertEquals(List.of("&7Price: &60 coins", "&8Add items first"), ShopText.sellTotalLines(null, 0, false));
        assertTrue(ShopText.sellTotalLines(quote, 1, true).get(0).contains("asking"));
    }

    @Test
    void replies() {
        assertEquals("&7You bought &f8 x Bread &7for &632 coins&7. You have &6968 coins &7left.",
                ShopText.bought(8, "Bread", 32, false, 968L));
        assertTrue(ShopText.bought(8, "Bread", 32, true, null).contains("/inbox"));
        assertEquals("&7You sold 2 item stacks for &6148 coins&7. You now have &61,148 coins&7.",
                ShopText.sold(148, 2, 1148L));
    }
}

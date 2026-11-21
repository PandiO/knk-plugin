package net.knightsandkings.knk.core.siege.shop;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The frozen catalogue and the shop's rows (siege shop DESIGN § 4.1.3, § 5.3, A17, A26; PandiO/knk-workspace#868).
 */
class ShopCatalogueTest {

    @Test
    void categoriesAndEntriesAreInShopOrder_andEmptyCategoriesAreLeftOut_A17() {
        ShopCatalogue catalogue = ShopFixtures.catalogue();
        assertEquals(List.of("siege-weapons", "consumables"), catalogue.categories().stream().map(ShopCategory::key).toList(),
                "no empty Magic category (§ 5.3.2)");
        assertEquals(List.of(11, 12, 13), catalogue.categories().get(1).entries().stream().map(ShopEntry::id).toList());
        assertEquals("Trebuchet", catalogue.entry(21).orElseThrow().name());
        assertTrue(catalogue.entry(99).isEmpty());
    }

    @Test
    void weaponEntriesAreLeftOutWhileSiegeWeaponsAreOff() {
        ShopCatalogue items = ShopFixtures.catalogue().withoutWeapons();
        assertEquals(List.of("consumables"), items.categories().stream().map(ShopCategory::key).toList());
        assertTrue(items.entry(21).isEmpty());
        assertFalse(items.isEmpty());
        assertTrue(ShopCatalogue.empty().isEmpty());
    }

    @Test
    void anEntryRowShowsPriceBoughtCooldownAndWhyNot_A26() {
        ShopEntry pearl = ShopFixtures.pearl();
        ShopEntryView fresh = new ShopEntryView(pearl, new ShopPurchaseService.EntryState(0, 300, 0, false), 400, "minecraft:ender_pearl", null);
        assertEquals(List.of("&7Price: &6300 siege points", "&7Bought: &f0/8", "", "&aClick to buy"), fresh.getLines());
        assertEquals("NORMAL", fresh.getDisplayMode());
        assertEquals("minecraft:ender_pearl", fresh.getMaterial());

        ShopEntryView cooling = new ShopEntryView(pearl, new ShopPurchaseService.EntryState(1, 350, 12_000, false), 100, null, null);
        assertEquals(List.of("&7Price: &6350 siege points", "&7Bought: &f1/8", "&cCooldown: 12 s", "",
                "&cNot enough siege points: 100/350"), cooling.getLines());
        assertEquals("DISABLED", cooling.getDisplayMode());

        ShopEntryView locked = new ShopEntryView(ShopFixtures.trebuchet(), new ShopPurchaseService.EntryState(0, 200, 0, false),
                1000, null, ShopRefusal.locked());
        assertEquals("&cLocked: unlock it in /menu → Siege Minigame → Siege unlocks.", locked.getLines().get(locked.getLines().size() - 1));
        assertEquals("DISABLED", locked.getDisplayMode());
        assertEquals("CROSSBOW", locked.getMaterial());

        ShopEntryView arrows = new ShopEntryView(ShopFixtures.arrows(), new ShopPurchaseService.EntryState(0, 40, 0, false), 40, null, null);
        assertEquals("&eArrow &7×16", arrows.getName());
        assertEquals(16, arrows.getAmount());
    }

    @Test
    void theHeaderShowsTheBalanceAndTheFooterSaysPurchasesAreFinal_P23() {
        ShopHeaderView header = new ShopHeaderView(650, "Consumables", 2);
        assertEquals("&7Siege points: &6650", header.getBalanceLine());
        assertEquals("&aConsumables", header.getCategoryName());
        assertEquals(List.of("&8Purchases are final; bought items end with the match."), header.getFooterLines());
        assertEquals("&7Nothing is for sale in this match.", new ShopHeaderView(0, null, 0).getEmptyLine());
        assertEquals(List.of("&71 offer", "", "&aClick to open"),
                new ShopCategoryView(ShopFixtures.catalogue().categories().get(0)).getLines());
    }
}

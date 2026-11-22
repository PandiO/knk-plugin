package net.knightsandkings.knk.paper.menu.content;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.menu.KnkMenuTemplate;
import net.knightsandkings.knk.core.production.shop.ShopMenuKeys;

/**
 * PandiO/knk-workspace#687: the seeded shop menus (knk-web-api {@code MenuTemplateSeed.Shops.cs}, exported to
 * {@code content-seeds.json}) validate against {@code ShopMenuFeature}, and keep the legacy V1 slots (legacy
 * inventory-menu-screens § 3.7, § 3.2.2).
 */
class ShopMenuSeedsTest {

    @Test
    void theThreeShopMenus_validateAgainstTheRegisteredFeature() {
        for (String key : ShopMenuKeys.MENU_KEYS) {
            assertDoesNotThrow(() -> ContentSeedFixture.validate(ContentSeedFixture.assemble(key), ContentFeatures.all()), key);
        }
    }

    @Test
    void shopMenu_keepsTheLegacyHeaderAndPagesTheCatalogue() {
        String json = json(ShopMenuKeys.SHOP_MENU);
        assertTrue(json.contains("shops.offer"), "catalogue source");
        assertTrue(json.contains("shops.sell"), "sell chest action");
        assertTrue(json.contains("shops.buys"), "sell chest only when the shop buys");
        assertTrue(json.contains("menu.page.next") && json.contains("menu.page.prev"), "paging");
        assertTrue(json.contains("\"key\":\"" + ShopMenuKeys.ITEM_MENU + "\""), "a row opens the item");
        assertEquals(List.of(0, 3, 4, 8), buttonSlots(ShopMenuKeys.SHOP_MENU));
    }

    @Test
    void itemMenu_hasAmountPriceDescriptionAndBack() {
        String json = json(ShopMenuKeys.ITEM_MENU);
        assertTrue(json.contains("shops.amount"));
        assertTrue(json.contains("shops.buyable"));
        assertTrue(json.contains("\"key\":\"" + ShopMenuKeys.CONFIRM_MENU + "\""));
        assertTrue(json.contains("$shop.getAmount$"));
        assertEquals(List.of(0, 3, 5, 6, 8), buttonSlots(ShopMenuKeys.ITEM_MENU));
    }

    @Test
    void confirmMenu_offersBothChannelsAndCancel() {
        String json = json(ShopMenuKeys.CONFIRM_MENU);
        assertTrue(json.contains("\"deliverTo\":\"Inventory\""), "confirm to the inventory");
        assertTrue(json.contains("\"deliverTo\":\"Inbox\""), "confirm to the reward inbox");
        assertTrue(json.contains("menu.back"));
        assertEquals(List.of(4, 8, 10, 12, 14), buttonSlots(ShopMenuKeys.CONFIRM_MENU));
    }

    private static String json(String key) {
        KnkMenuTemplate template = ContentSeedFixture.templates().get(key);
        return template.toString().replace(" ", "");
    }

    private static List<Integer> buttonSlots(String key) {
        KnkMenuTemplate template = ContentSeedFixture.templates().get(key);
        List<Integer> slots = new ArrayList<>();
        template.sections().stream().filter(s -> "StaticButtons".equalsIgnoreCase(s.kind()))
                .forEach(buttons -> buttons.items().forEach(item -> slots.add(item.slotOverride())));
        slots.sort(Integer::compare);
        return slots;
    }
}

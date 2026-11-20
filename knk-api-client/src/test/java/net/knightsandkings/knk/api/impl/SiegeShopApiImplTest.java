package net.knightsandkings.knk.api.impl;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.core.siege.shop.ShopBrakes;
import net.knightsandkings.knk.core.siege.shop.ShopCatalogue;
import net.knightsandkings.knk.core.siege.shop.ShopCurve;
import net.knightsandkings.knk.core.siege.shop.ShopEntry;
import net.knightsandkings.knk.core.siege.shop.ShopEntryKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The effective catalogue as knk-web-api's {@code GET api/SiegeShop/catalogue} (L2, #867) sends it, read by the plugin
 * once per match (siege shop § 5.3, P25; PandiO/knk-workspace#868).
 */
class SiegeShopApiImplTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final String JSON = """
            {"gameMode":"Siege","siegeScenarioId":4,"categories":[
              {"id":2,"key":"consumables","name":"Consumables","iconMaterial":"minecraft:potion","sortOrder":1,"entries":[
                {"id":11,"categoryId":2,"kind":"Item","source":"Overridden","itemBlueprintId":101,
                 "itemBlueprintName":"Ender Pearl","quantity":1,"siegeWeaponTemplateId":null,"siegeWeaponTemplateName":null,
                 "displayName":null,"iconMaterial":null,"price":350,"priceStep":50,"priceCurve":"Linear",
                 "priceGrowthFactor":null,"priceMax":null,"cooldownSeconds":20,"cooldownStepSeconds":10,
                 "cooldownCurve":"Exponential","cooldownGrowthFactor":null,"cooldownMaxSeconds":null,"maxPerMatch":4,
                 "sortOrder":0},
                {"id":19,"categoryId":2,"kind":"MagicAction","price":10,"quantity":1,"sortOrder":1}]},
              {"id":1,"key":"siege-weapons","name":"Siege weapons","iconMaterial":null,"sortOrder":0,"entries":[
                {"id":21,"categoryId":1,"kind":"SiegeWeapon","source":"Default","itemBlueprintId":null,"quantity":1,
                 "siegeWeaponTemplateId":5,"siegeWeaponTemplateName":"Trebuchet","price":200,"priceCurve":"None",
                 "cooldownCurve":"None","sortOrder":0}]}]}
            """;

    @Test
    void mapsTheEffectiveCatalogue_withResolvedPricesAndBrakes() throws Exception {
        ShopCatalogue catalogue = SiegeShopApiImpl.toCore(
                objectMapper.readValue(JSON, SiegeShopApiImpl.CatalogueDto.class), "Siege", 4);

        assertEquals("Siege", catalogue.gameMode());
        assertEquals(4, catalogue.scenarioId());
        assertEquals(List.of("siege-weapons", "consumables"), catalogue.categories().stream().map(c -> c.key()).toList());

        ShopEntry pearl = catalogue.entry(11).orElseThrow();
        assertEquals(ShopEntryKind.ITEM, pearl.kind());
        assertEquals(101, pearl.itemBlueprintId());
        assertEquals("Ender Pearl", pearl.name());
        assertEquals(350, pearl.price());
        assertEquals(ShopCurve.LINEAR, pearl.priceCurve());
        assertEquals(ShopCurve.EXPONENTIAL, pearl.cooldownCurve());
        assertNull(pearl.cooldownGrowthFactor(), "a missing growth factor means 1.5 (§ 5.4)");
        assertEquals(45.0, ShopBrakes.cooldownSeconds(pearl, 3), 1e-9);
        assertEquals(4, pearl.maxPerMatch());

        ShopEntry trebuchet = catalogue.entry(21).orElseThrow();
        assertEquals(ShopEntryKind.SIEGE_WEAPON, trebuchet.kind());
        assertEquals(5, trebuchet.siegeWeaponTemplateId());
        assertEquals(200, trebuchet.price());

        assertTrue(catalogue.entry(19).isEmpty(), "a kind the plugin doesn't know is left out");
        assertTrue(catalogue.withoutWeapons().entry(21).isEmpty());
    }

    @Test
    void anEmptyAnswerIsAnEmptyCatalogue() {
        assertTrue(SiegeShopApiImpl.toCore(null, "Siege", null).isEmpty());
    }
}

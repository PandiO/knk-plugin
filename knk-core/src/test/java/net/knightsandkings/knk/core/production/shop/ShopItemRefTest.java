package net.knightsandkings.knk.core.production.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/** PandiO/knk-workspace#687: how the shop menus name one item of a shop between them ({@code ctx.item}). */
class ShopItemRefTest {

    @Test
    void ref_prefersTheEntryThenTheInstanceThenTheBlueprint() {
        assertEquals("e12", ShopFixtures.bread().ref().toString());
        assertEquals("i900", ShopFixtures.sword().ref().toString());
        assertEquals("b15", ShopFixtures.flour(3).ref().toString());
    }

    @Test
    void parse_roundTripsAndRefusesNonsense() {
        assertEquals(ShopFixtures.bread().ref(), ShopItemRef.parse("e12"));
        assertEquals(ShopFixtures.sword().ref(), ShopItemRef.parse(" I900 "));
        assertNull(ShopItemRef.parse(null));
        assertNull(ShopItemRef.parse("e"));
        assertNull(ShopItemRef.parse("x12"));
        assertNull(ShopItemRef.parse("e-3"));
        assertNull(ShopItemRef.parse("e0"));
        assertNull(ShopItemRef.parse("eabc"));
    }

    @Test
    void request_fieldsAreTheOneThatMatches() {
        ShopItemRef entry = ShopItemRef.parse("e12");
        assertEquals(12, entry.catalogueEntryId());
        assertNull(entry.itemInstanceId());
        assertNull(entry.itemBlueprintId());
        assertEquals(900L, ShopItemRef.parse("i900").itemInstanceId());
        assertEquals(15, ShopItemRef.parse("b15").itemBlueprintId());
    }

    @Test
    void offer_findsTheItem() {
        ShopOffer offer = ShopFixtures.offer(ShopFixtures.bread(), ShopFixtures.sword());
        assertSame(offer.items().get(1), offer.find(ShopItemRef.parse("i900")));
        assertNull(offer.find(ShopItemRef.parse("b11"))); // bread is offered by its entry, not as loose stock
        assertNull(offer.find(null));
    }
}

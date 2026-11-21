package net.knightsandkings.knk.core.production.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** PandiO/knk-workspace#687: what the sell menu sends for the stacks a player picked. */
class SellBasketTest {

    private static SellBasket.Pick bread(int slot, long amount) {
        return new SellBasket.Pick(slot, 11, null, amount, "Bread");
    }

    @Test
    void lines_addUpABlueprintAndKeepEachUniqueItem() {
        SellBasket basket = new SellBasket();
        assertTrue(basket.add(bread(0, 64)));
        assertTrue(basket.add(new SellBasket.Pick(1, 20, 900L, 1, "Sword")));
        assertTrue(basket.add(bread(2, 10)));
        assertTrue(basket.add(new SellBasket.Pick(3, 15, null, 5, "Flour")));

        assertEquals(List.of(ShopSellLine.blueprint(11, 74), ShopSellLine.instance(900), ShopSellLine.blueprint(15, 5)),
                basket.lines());
        assertEquals(0, basket.lineOf(0));
        assertEquals(1, basket.lineOf(1));
        assertEquals(0, basket.lineOf(2)); // the second bread stack is on the bread line
        assertEquals(2, basket.lineOf(3));
        assertEquals(-1, basket.lineOf(4));
    }

    @Test
    void add_refusesTheSameSlotTwiceAnEmptyPickAndAFullBasket() {
        SellBasket basket = new SellBasket();
        assertTrue(basket.add(bread(0, 1)));
        assertFalse(basket.add(bread(0, 1)));
        assertFalse(basket.add(bread(1, 0)));
        assertFalse(basket.add(new SellBasket.Pick(2, null, null, 1, "?")));
        for (int slot = 1; slot < SellBasket.CAPACITY; slot++) {
            assertTrue(basket.add(bread(slot, 1)));
        }
        assertTrue(basket.full());
        assertFalse(basket.add(bread(30, 1)));
    }

    @Test
    void remove_takesOutOnePick() {
        SellBasket basket = new SellBasket();
        basket.add(bread(0, 3));
        basket.add(bread(5, 2));
        assertEquals(5, basket.remove(1).inventorySlot());
        assertEquals(List.of(ShopSellLine.blueprint(11, 3)), basket.lines());
        assertEquals(null, basket.remove(4));
        assertFalse(basket.holdsSlot(5));
    }

    @Test
    void layout_keepsTheLegacyDropZone() {
        assertEquals(19, SellMenuLayout.pickSlot(0));
        assertEquals(25, SellMenuLayout.pickSlot(SellBasket.CAPACITY - 1));
        assertEquals(0, SellMenuLayout.pickIndex(19));
        assertEquals(6, SellMenuLayout.pickIndex(25));
        assertEquals(-1, SellMenuLayout.pickIndex(26));
        assertEquals(-1, SellMenuLayout.pickIndex(SellMenuLayout.SELL_SLOT));
    }
}

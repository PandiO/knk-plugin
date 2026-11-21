package net.knightsandkings.knk.core.production.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** PandiO/knk-workspace#687: the legacy Amount button (1, 8, 16, 32, 64) and the limits of one purchase. */
class ShopAmountsTest {

    @Test
    void next_cyclesTheStepsAndStartsOverFromAnythingElse() {
        assertEquals(8, ShopAmounts.next(1));
        assertEquals(16, ShopAmounts.next(8));
        assertEquals(64, ShopAmounts.next(32));
        assertEquals(1, ShopAmounts.next(64));
        assertEquals(1, ShopAmounts.next(5));
    }

    @Test
    void of_isOneForUniqueItemsAndCappedByLimitedStock() {
        assertEquals(1, ShopAmounts.of(64, ShopFixtures.sword()));
        assertEquals(3, ShopAmounts.of(16, ShopFixtures.flour(3)));
        assertEquals(64, ShopAmounts.of(64, ShopFixtures.bread())); // unlimited
        assertEquals(1, ShopAmounts.of(0, ShopFixtures.bread()));
        assertEquals(16, ShopAmounts.of(16, ShopFixtures.flour(0))); // sold out: the quote refuses, the amount stays
        assertTrue(ShopAmounts.of(Long.MAX_VALUE, null) <= ShopAmounts.MAX);
    }
}

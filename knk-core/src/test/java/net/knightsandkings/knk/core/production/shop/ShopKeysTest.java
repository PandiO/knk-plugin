package net.knightsandkings.knk.core.production.shop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** PandiO/knk-workspace#687: keys fit the API's rule (1-60 of {@code A-Z a-z 0-9 : _ . -}) and differ per click. */
class ShopKeysTest {

    @Test
    void keysAreValidAndFresh() {
        String a = ShopKeys.purchase(Integer.MAX_VALUE, Integer.MAX_VALUE);
        String b = ShopKeys.purchase(Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertTrue(ShopKeys.valid(a), a);
        assertTrue(ShopKeys.valid(ShopKeys.sale(Integer.MAX_VALUE, Integer.MAX_VALUE)));
        assertNotEquals(a, b);
        assertTrue(a.startsWith("buy-s"));
        assertFalse(ShopKeys.valid("has space"));
        assertFalse(ShopKeys.valid("x".repeat(61)));
    }
}

package net.knightsandkings.knk.core.production.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/** PandiO/knk-workspace#687: every 409 code of the shop routes (API contract shops § 4.4) has its own message. */
class ShopRefusalTest {

    @Test
    void everyContractCodeIsKnown() {
        String[] codes = {"ProductionDisabled", "NotConfirmed", "PriceChanged", "NotForSale", "SoldOut", "NotEnoughStock",
                "StorageLocked", "InsufficientFunds", "InboxFull", "NoTown", "TownFundsInsufficient", "CategoryNotAccepted",
                "NoPrice", "NotResolved", "InStorage", "Soulbound", "NoRoom", "IdempotencyKeyReuse"};
        for (String code : codes) {
            ShopRefusal refusal = ShopRefusal.of(code);
            assertNotEquals(ShopRefusal.OTHER, refusal, code);
            assertEquals(code, refusal.code());
        }
    }

    @Test
    void fromBody_readsTheCode() {
        assertEquals(ShopRefusal.PRICE_CHANGED, ShopRefusal.fromBody("{\"code\":\"PriceChanged\",\"error\":\"now 5\"}"));
        assertEquals(ShopRefusal.INSUFFICIENT_FUNDS, ShopRefusal.fromBody("{ \"error\": \"x\", \"code\" : \"insufficientfunds\" }"));
        assertEquals(ShopRefusal.OTHER, ShopRefusal.fromBody("{\"error\":\"conflict\"}"));
        assertEquals(ShopRefusal.OTHER, ShopRefusal.fromBody(null));
    }

    @Test
    void describe_usesTheApiMessageOnlyForUnknownCodes() {
        assertEquals(ShopRefusal.SOLD_OUT.message(), ShopRefusal.describe("SoldOut", "whatever"));
        assertEquals("Something new", ShopRefusal.describe("BrandNew", "Something new"));
        assertEquals(ShopRefusal.OTHER.message(), ShopRefusal.describe(null, null));
    }
}

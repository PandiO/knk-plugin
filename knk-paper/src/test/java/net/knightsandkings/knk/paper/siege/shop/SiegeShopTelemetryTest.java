package net.knightsandkings.knk.paper.siege.shop;

import net.knightsandkings.knk.core.siege.shop.ShopCurve;
import net.knightsandkings.knk.core.siege.shop.ShopEntry;
import net.knightsandkings.knk.core.siege.shop.ShopEntryKind;
import net.knightsandkings.knk.core.siege.shop.ShopPurchaseResult;
import net.knightsandkings.knk.core.telemetry.TelemetryBuffer;
import net.knightsandkings.knk.core.telemetry.TelemetryClientConfig;
import net.knightsandkings.knk.core.telemetry.TelemetryEvent;
import net.knightsandkings.knk.core.telemetry.TelemetryEventNames;
import net.knightsandkings.knk.paper.telemetry.TelemetryEmitter;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A11 / P27 (PandiO/knk-workspace#868): every charged purchase is one {@code siege.shop_purchase} event. */
class SiegeShopTelemetryTest {

    @Test
    void aPurchaseIsOneEventWithEntryPriceAndPurchaseNumber_A11() {
        TelemetryBuffer buffer = new TelemetryBuffer(10);
        TelemetryEmitter emitter = new TelemetryEmitter(buffer, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), "paper", "1");
        emitter.setUserIds(id -> 7);
        emitter.updateConfig(new TelemetryClientConfig(true, Set.of(), List.of(), Set.of(), Set.of(), Set.of()));
        ShopEntry pearl = new ShopEntry(11, 2, ShopEntryKind.ITEM, "Default", 101, "Ender Pearl", 1, null, null, null, null,
                300, 50L, ShopCurve.LINEAR, null, null, 20.0, null, ShopCurve.NONE, null, null, 8, 0);

        new SiegeShopTelemetry(emitter).purchased(3, new UUID(0, 1),
                new ShopPurchaseResult.Bought(pearl, 350, 2, 100, "shop:m:p:11:2"));

        List<TelemetryEvent> events = buffer.drain(10);
        assertEquals(1, events.size());
        TelemetryEvent e = events.get(0);
        assertEquals(TelemetryEventNames.SIEGE_SHOP_PURCHASE, e.name());
        assertEquals("siege.shop_purchase", e.name());
        assertEquals(11, ((Number) e.payload().get("entryId")).intValue());
        assertEquals(2, ((Number) e.payload().get("categoryId")).intValue());
        assertEquals("item", e.payload().get("kind"));
        assertEquals(350L, ((Number) e.payload().get("price")).longValue());
        assertEquals(2, ((Number) e.payload().get("purchaseNo")).intValue());
        assertEquals(3, ((Number) e.payload().get("lobbyId")).intValue());
        assertEquals(7, e.userId());
    }
}

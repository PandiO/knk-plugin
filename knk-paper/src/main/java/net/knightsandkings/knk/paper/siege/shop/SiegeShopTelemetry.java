package net.knightsandkings.knk.paper.siege.shop;

import net.knightsandkings.knk.core.siege.shop.ShopPurchaseResult;
import net.knightsandkings.knk.core.telemetry.TelemetryEvent;
import net.knightsandkings.knk.core.telemetry.TelemetryEventNames;
import net.knightsandkings.knk.paper.telemetry.TelemetryEmitter;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Siege shop purchases in the diagnostic telemetry for the balance runs (siege shop P27, A11, #651;
 * PandiO/knk-workspace#868): one {@code siege.shop_purchase} event per charged purchase with the entry, its kind and
 * category, the price and the player's purchase number of that entry. The catalog allows one dot in a name, so not
 * {@code siege.shop.purchase}. Main thread.
 */
public final class SiegeShopTelemetry {

    private final TelemetryEmitter emitter;

    public SiegeShopTelemetry(TelemetryEmitter emitter) {
        this.emitter = Objects.requireNonNull(emitter, "emitter");
    }

    public void purchased(int lobbyId, UUID player, ShopPurchaseResult.Bought purchase) {
        emitter.event(TelemetryEventNames.SIEGE_SHOP_PURCHASE)
                .player(player)
                .outcome(TelemetryEvent.Outcome.INFO)
                .object("siege_lobby", lobbyId)
                .put("lobbyId", lobbyId)
                .put("entryId", purchase.entry().id())
                .put("categoryId", purchase.entry().categoryId())
                .put("kind", purchase.entry().kind().name().toLowerCase(Locale.ROOT))
                .put("price", purchase.price())
                .put("purchaseNo", purchase.purchaseNumber())
                .emit();
    }
}

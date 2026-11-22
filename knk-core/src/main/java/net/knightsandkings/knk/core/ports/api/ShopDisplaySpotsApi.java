package net.knightsandkings.knk.core.ports.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.production.shop.display.DisplaySpot;

/**
 * Display spots of shops and their best sellers, game server side (display stalls P12, PandiO/knk-workspace#250;
 * knk-web-api {@code ShopDisplaySpotsController} and {@code ShopTradeStatsController}). Every call takes the plugin key.
 * Failures carry the status code ({@code ApiException}); 409 is a taken block or a full shop.
 */
public interface ShopDisplaySpotsApi {

    /** A new spot, as {@code /knk shop display add} registers it. */
    record SpotDraft(int shopId, String world, int x, int y, int z, DisplaySpot.Facing facing, String label) {
    }

    /** {@code POST api/ShopDisplaySpots/search}, every page: all spots, or one shop's when {@code shopId} is set. */
    CompletableFuture<List<DisplaySpot>> list(Integer shopId);

    /** {@code POST api/ShopDisplaySpots}. */
    CompletableFuture<DisplaySpot> create(SpotDraft draft);

    /** {@code PUT api/ShopDisplaySpots/{id}} with the pinned entry only; null clears the pin. */
    CompletableFuture<DisplaySpot> pin(int spotId, Integer catalogueEntryId);

    /** {@code DELETE api/ShopDisplaySpots/{id}}. */
    CompletableFuture<Void> delete(int spotId);

    /**
     * {@code POST api/ShopTradeStats/search} for one shop from {@code since} (UTC hours): units sold per blueprint id, the
     * best sellers of the selection.
     */
    CompletableFuture<Map<Integer, Long>> unitsSold(int shopId, Instant since);
}

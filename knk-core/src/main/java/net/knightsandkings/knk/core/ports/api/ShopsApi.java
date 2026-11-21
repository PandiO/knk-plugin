package net.knightsandkings.knk.core.ports.api;

import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.production.shop.ShopOffer;
import net.knightsandkings.knk.core.production.shop.ShopPurchaseRequest;
import net.knightsandkings.knk.core.production.shop.ShopPurchaseResult;
import net.knightsandkings.knk.core.production.shop.ShopQuote;
import net.knightsandkings.knk.core.production.shop.ShopSellQuote;
import net.knightsandkings.knk.core.production.shop.ShopSellRequest;
import net.knightsandkings.knk.core.production.shop.ShopSellResult;

/**
 * Buying from and selling to shops, game server side (PandiO/knk-workspace#687; the contract of P10 #248, knk-web-api
 * {@code ShopsController}, API contract shops § 4). Every route takes the plugin key. Failures carry the status code
 * ({@code ApiException}); a refusal is 409 {@code { "code", "error" }}
 * ({@link net.knightsandkings.knk.core.production.shop.ShopRefusal#fromBody}).
 */
public interface ShopsApi {

    /** {@code GET api/Shops/{id}/offer}. */
    CompletableFuture<ShopOffer> offer(int shopId);

    /** {@code POST api/Shops/{id}/quote}: price and refusal, changes nothing. */
    CompletableFuture<ShopQuote> quote(int shopId, ShopPurchaseRequest request);

    /** {@code POST api/Shops/{id}/purchase}: one transaction; idempotent on the key. */
    CompletableFuture<ShopPurchaseResult> purchase(int shopId, ShopPurchaseRequest request);

    /** {@code POST api/Shops/{id}/sell-quote}: buy-back prices, changes nothing. */
    CompletableFuture<ShopSellQuote> sellQuote(int shopId, ShopSellRequest request);

    /** {@code POST api/Shops/{id}/sell}: all lines or none; idempotent on the key. */
    CompletableFuture<ShopSellResult> sell(int shopId, ShopSellRequest request);
}

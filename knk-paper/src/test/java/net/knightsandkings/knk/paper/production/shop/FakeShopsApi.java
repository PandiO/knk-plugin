package net.knightsandkings.knk.paper.production.shop;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.ShopsApi;
import net.knightsandkings.knk.core.production.shop.ShopOffer;
import net.knightsandkings.knk.core.production.shop.ShopOfferItem;
import net.knightsandkings.knk.core.production.shop.ShopPurchaseRequest;
import net.knightsandkings.knk.core.production.shop.ShopPurchaseResult;
import net.knightsandkings.knk.core.production.shop.ShopQuote;
import net.knightsandkings.knk.core.production.shop.ShopSellQuote;
import net.knightsandkings.knk.core.production.shop.ShopSellRequest;
import net.knightsandkings.knk.core.production.shop.ShopSellResult;

/** The P10 shop routes in memory (PandiO/knk-workspace#687): a bakery with bread, flour and a unique sword. */
final class FakeShopsApi implements ShopsApi {

    static final ShopOfferItem BREAD = new ShopOfferItem(12, 11, "Bread", null, 4, "Static", null, false, true, 0, List.of());
    static final ShopOfferItem FLOUR = new ShopOfferItem(null, 15, "Flour", null, 3, "Formula", 0L, true, false,
            Integer.MAX_VALUE, List.of());
    static final ShopOfferItem SWORD = new ShopOfferItem(null, 20, "Iron sword", 900L, 150, "Formula", 1L, false, false,
            Integer.MAX_VALUE, List.of("Sharpness 2"));

    long unitPrice = 4;
    boolean buysFromPlayers = true;
    final List<ShopPurchaseRequest> quotes = new ArrayList<>();
    final List<ShopPurchaseRequest> purchases = new ArrayList<>();
    final List<ShopSellRequest> sellQuotes = new ArrayList<>();
    final List<ShopSellRequest> sales = new ArrayList<>();
    /** Failures for the next purchases or sales, in order (null = answer normally). */
    final Deque<RuntimeException> failures = new ArrayDeque<>();
    /** Lines whose blueprint the shop refuses. */
    Integer refusedBlueprint;

    static ApiException conflict(String code) {
        return new ApiException("http://api.test", 409, "Conflict", "{\"code\":\"" + code + "\",\"error\":\"x\"}");
    }

    static ApiException lost() {
        return new ApiException("timeout", new java.io.IOException("timeout"));
    }

    @Override
    public CompletableFuture<ShopOffer> offer(int shopId) {
        return CompletableFuture.completedFuture(new ShopOffer(shopId, "Bakery", "Processing", 2, "Bakery", 3, "Aldor", false,
                buysFromPlayers, List.of(new ShopOfferItem(12, 11, "Bread", null, unitPrice, "Static", null, false, true, 0,
                        List.of()), FLOUR, SWORD)));
    }

    @Override
    public CompletableFuture<ShopQuote> quote(int shopId, ShopPurchaseRequest request) {
        quotes.add(request);
        return CompletableFuture.completedFuture(new ShopQuote(shopId, request.item().catalogueEntryId(), 11, "Bread", null,
                request.amount(), unitPrice, unitPrice * request.amount(), "Static", null, null, null));
    }

    @Override
    public CompletableFuture<ShopPurchaseResult> purchase(int shopId, ShopPurchaseRequest request) {
        purchases.add(request);
        RuntimeException failure = failures.poll();
        if (failure != null) {
            return CompletableFuture.failedFuture(failure);
        }
        return CompletableFuture.completedFuture(new ShopPurchaseResult(shopId, 11, null, request.amount(),
                request.expectedUnitPrice(), request.expectedUnitPrice() * request.amount(), request.deliverTo().apiName(),
                null, 968L, false));
    }

    @Override
    public CompletableFuture<ShopSellQuote> sellQuote(int shopId, ShopSellRequest request) {
        sellQuotes.add(request);
        return CompletableFuture.completedFuture(answer(shopId, request));
    }

    @Override
    public CompletableFuture<ShopSellResult> sell(int shopId, ShopSellRequest request) {
        sales.add(request);
        RuntimeException failure = failures.poll();
        if (failure != null) {
            return CompletableFuture.failedFuture(failure);
        }
        ShopSellQuote quote = answer(shopId, request);
        return CompletableFuture.completedFuture(new ShopSellResult(shopId, quote.total(), quote.lines(), 1040L, false));
    }

    private ShopSellQuote answer(int shopId, ShopSellRequest request) {
        List<ShopSellQuote.Line> lines = new ArrayList<>();
        long total = 0;
        boolean accepted = true;
        for (int i = 0; i < request.lines().size(); i++) {
            var line = request.lines().get(i);
            boolean refused = refusedBlueprint != null && refusedBlueprint.equals(line.itemBlueprintId());
            long unit = line.unique() ? 75 : 2;
            lines.add(new ShopSellQuote.Line(i, line.itemBlueprintId(), "Item", line.itemInstanceId(), line.amount(),
                    refused ? 0 : unit, refused ? 0 : unit * line.amount(), refused ? "CategoryNotAccepted" : null, null));
            if (refused) {
                accepted = false;
            } else {
                total += unit * line.amount();
            }
        }
        return new ShopSellQuote(shopId, lines, total, accepted, true);
    }
}

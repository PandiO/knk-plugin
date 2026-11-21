package net.knightsandkings.knk.paper.scenario.scenarios;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import net.knightsandkings.knk.core.items.identity.ItemIdentityResolution;
import net.knightsandkings.knk.core.ports.api.ShopsApi;
import net.knightsandkings.knk.core.production.shop.ShopOffer;
import net.knightsandkings.knk.core.production.shop.ShopOfferItem;
import net.knightsandkings.knk.core.production.shop.ShopPurchaseRequest;
import net.knightsandkings.knk.core.production.shop.ShopPurchaseResult;
import net.knightsandkings.knk.core.production.shop.ShopQuote;
import net.knightsandkings.knk.core.production.shop.ShopSellLine;
import net.knightsandkings.knk.core.production.shop.ShopSellQuote;
import net.knightsandkings.knk.core.production.shop.ShopSellRequest;
import net.knightsandkings.knk.core.production.shop.ShopSellResult;

/**
 * The shop scenario's stand-ins (PandiO/knk-workspace#687): an in-memory {@link ShopsApi} for one bakery that sells bread
 * (catalogue entry 12, 4 coins each, unlimited) and buys it back for 2 coins each, recording every call; and an item
 * identity resolver that knows bread as its blueprint, so no API is needed.
 */
final class ScenarioShops {

    static final int SHOP = 5;
    static final int BREAD = ScenarioInbox.BREAD;

    final List<ShopPurchaseRequest> purchases = new ArrayList<>();
    final List<ShopSellRequest> sellQuotes = new ArrayList<>();
    final List<ShopSellRequest> sales = new ArrayList<>();

    final ShopsApi api = new ShopsApi() {
        public CompletableFuture<ShopOffer> offer(int shopId) {
            return CompletableFuture.completedFuture(new ShopOffer(shopId, "Scenario bakery", "Trading", 1, "Bakery", 1, "Town",
                    false, true, List.of(new ShopOfferItem(12, BREAD, "Bread", null, 4, "Static", null, false, false, 0, List.of()))));
        }

        public CompletableFuture<ShopQuote> quote(int shopId, ShopPurchaseRequest request) {
            return CompletableFuture.completedFuture(new ShopQuote(shopId, 12, BREAD, "Bread", null, request.amount(), 4,
                    4 * request.amount(), "Static", null, null, null));
        }

        public CompletableFuture<ShopPurchaseResult> purchase(int shopId, ShopPurchaseRequest request) {
            purchases.add(request);
            return CompletableFuture.completedFuture(new ShopPurchaseResult(shopId, BREAD, null, request.amount(), 4,
                    4 * request.amount(), request.deliverTo().apiName(), null, 100L - 4 * request.amount(), false));
        }

        public CompletableFuture<ShopSellQuote> sellQuote(int shopId, ShopSellRequest request) {
            sellQuotes.add(request);
            return CompletableFuture.completedFuture(answer(shopId, request));
        }

        public CompletableFuture<ShopSellResult> sell(int shopId, ShopSellRequest request) {
            sales.add(request);
            ShopSellQuote quote = answer(shopId, request);
            return CompletableFuture.completedFuture(new ShopSellResult(shopId, quote.total(), quote.lines(), 140L, false));
        }
    };

    private static ShopSellQuote answer(int shopId, ShopSellRequest request) {
        List<ShopSellQuote.Line> lines = new ArrayList<>();
        long total = 0;
        for (int i = 0; i < request.lines().size(); i++) {
            ShopSellLine line = request.lines().get(i);
            lines.add(new ShopSellQuote.Line(i, line.itemBlueprintId(), "Bread", null, line.amount(), 2, 2 * line.amount(), null,
                    null));
            total += 2 * line.amount();
        }
        return new ShopSellQuote(shopId, lines, total, true, true);
    }

    /** Bread resolves to its blueprint; anything else is unresolved. */
    static Function<ItemStack, ItemIdentityResolution> identity() {
        return stack -> stack.getType() == Material.BREAD
                ? new ItemIdentityResolution(ItemIdentityResolution.Kind.VANILLA_MATCH, BREAD, null, null, false)
                : new ItemIdentityResolution(ItemIdentityResolution.Kind.UNRESOLVED, null, null,
                        ItemIdentityResolution.UnresolvedReason.NO_MATCH, false);
    }
}

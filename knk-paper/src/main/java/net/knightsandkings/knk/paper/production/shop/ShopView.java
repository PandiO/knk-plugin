package net.knightsandkings.knk.paper.production.shop;

import java.util.List;

import net.knightsandkings.knk.core.production.shop.ShopOffer;
import net.knightsandkings.knk.core.production.shop.ShopOfferItem;
import net.knightsandkings.knk.core.production.shop.ShopQuote;
import net.knightsandkings.knk.core.production.shop.ShopText;

/**
 * The variable root {@code shop} of the shop menus (PandiO/knk-workspace#687): the shop of {@code ctx.shopId}, the item of
 * {@code ctx.item}, the amount the player picked, the quote and the player's coins. Built on the main thread from what the
 * last reads left ({@link ShopService}); null parts read as "loading".
 */
public final class ShopView {

    private final int shopId;
    private final ShopOffer offer;
    private final ShopOfferItem item;
    private final long amount;
    private final ShopQuote quote;
    private final Long changedFrom;
    private final Long coins;

    ShopView(int shopId, ShopOffer offer, ShopOfferItem item, long amount, ShopQuote quote, Long changedFrom, Long coins) {
        this.shopId = shopId;
        this.offer = offer;
        this.item = item;
        this.amount = amount;
        this.quote = quote;
        this.changedFrom = changedFrom;
        this.coins = coins;
    }

    ShopOffer offer() {
        return offer;
    }

    ShopOfferItem item() {
        return item;
    }

    ShopQuote quote() {
        return quote;
    }

    public String getShopId() {
        return String.valueOf(shopId);
    }

    public String getShopName() {
        return "&6" + (offer == null ? "Shop" : offer.shopName());
    }

    public String getInfoName() {
        return "&6Information about " + (offer == null ? "this shop" : offer.shopName());
    }

    public List<String> getShopLines() {
        return ShopText.shopLines(offer);
    }

    public String getCoinsLine() {
        return ShopText.coinsLine(coins);
    }

    public String getEmptyLine() {
        if (offer == null) {
            return "&8Loading the shop...";
        }
        return offer.items().isEmpty() ? "&7This shop has nothing on offer right now." : "&7Click an item for more information";
    }

    public String getItem() {
        return item == null ? "" : item.ref().toString();
    }

    public String getItemName() {
        return "&f" + (item == null ? "Item" : item.itemBlueprintName());
    }

    public String getAmount() {
        return String.valueOf(amount);
    }

    public String getAmountName() {
        return "&eAmount: &f" + ShopText.units(amount);
    }

    public List<String> getAmountLines() {
        if (item != null && item.unique()) {
            return List.of("&7Unique items are sold one at a time");
        }
        return List.of("&7Click to change how many", "&7you buy at once (1, 8, 16, 32, 64)");
    }

    public String getPriceName() {
        return item == null ? "&cNot for sale" : "&6This item costs: " + ShopText.coins(item.unitPrice());
    }

    public List<String> getPriceLines() {
        return ShopText.priceLines(item, amount, coins);
    }

    public List<String> getDescriptionLines() {
        return ShopText.descriptionLines(item);
    }

    public List<String> getConfirmLines() {
        return ShopText.confirmLines(item == null ? null : item.itemBlueprintName(), amount, quote, changedFrom, coins);
    }

    public String getTotalLine() {
        return quote == null ? "&8Asking the shop..." : "&7Total: &6" + ShopText.coins(quote.totalPrice());
    }
}

package net.knightsandkings.knk.core.production.shop;

import java.util.List;

/**
 * The ids the seeded shop menus and the plugin share (PandiO/knk-workspace#687; knk-web-api
 * {@code MenuTemplateSeed.Shops.cs}). Not the siege shop's ({@code core.siege.shop.ShopMenuIds}).
 */
public final class ShopMenuKeys {

    /** The shop menu: info header, paged catalogue ({@code ctx.shopId}). */
    public static final String SHOP_MENU = "shops.shop";
    /** The shop item information menu ({@code ctx.shopId}, {@code ctx.item}). */
    public static final String ITEM_MENU = "shops.item";
    /** The purchase confirmation ({@code ctx.shopId}, {@code ctx.item}, {@code ctx.amount}). */
    public static final String CONFIRM_MENU = "shops.confirm";
    public static final List<String> MENU_KEYS = List.of(SHOP_MENU, ITEM_MENU, CONFIRM_MENU);

    public static final String CTX_SHOP_ID = "shopId";
    public static final String CTX_ITEM = "item";
    public static final String CTX_AMOUNT = "amount";

    /** Variable root: the shop of {@code ctx.shopId}, the item of {@code ctx.item}, the quote and the player's coins. */
    public static final String ROOT = "shop";
    /** Row source: the catalogue of {@code ctx.shopId}, one row per offered item. */
    public static final String SOURCE_OFFER = "shops.offer";
    /** Row source: the one item of {@code ctx.item}, shown as the item itself. */
    public static final String SOURCE_ITEM = "shops.item";
    /** Action: buy ({@code deliverTo} {@code Inventory} or {@code Inbox}) after the confirmation. */
    public static final String ACTION_PURCHASE = "shops.purchase";
    /** Action: the next amount step of the item of {@code ctx.item} ({@link ShopAmounts#next}). */
    public static final String ACTION_AMOUNT = "shops.amount";
    /** Action: open the sell menu of {@code ctx.shopId}. */
    public static final String ACTION_SELL = "shops.sell";
    /** Condition: the shop of {@code ctx.shopId} buys from players. */
    public static final String CONDITION_BUYS = "shops.buys";
    /** Condition: the item of {@code ctx.item} can be bought in game (in stock, not a unique item for now). */
    public static final String CONDITION_BUYABLE = "shops.buyable";

    private ShopMenuKeys() {
    }
}

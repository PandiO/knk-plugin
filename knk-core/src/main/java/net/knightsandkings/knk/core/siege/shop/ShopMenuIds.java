package net.knightsandkings.knk.core.siege.shop;

/**
 * Every menu key, variable root, content source, action and condition id of the siege shop menus (siege shop DESIGN
 * § 7.2.3, P24; PandiO/knk-workspace#868). knk-web-api's {@code MenuTemplateSeed.Siege.cs} references the same
 * strings; the seed ↔ plugin contract test (knk-api-client {@code SiegeMenuSeedContractTest}) checks them.
 */
public final class ShopMenuIds {
    private ShopMenuIds() {
    }

    // ---- menus (the "siege." prefix makes the siege menu bridge repaint them on lobby changes) ----
    public static final String MENU_SHOP = "siege.shop";
    public static final String MENU_CATEGORY = "siege.shop.category";

    /** Context parameter of {@link #MENU_CATEGORY}. */
    public static final String CTX_CATEGORY_ID = "categoryId";

    // ---- variable root ----
    /** {@link ShopHeaderView}: balance, the category of {@code ctx.categoryId}, footer. */
    public static final String ROOT_SHOP = "siegeShop";

    // ---- content sources ----
    /** {@link ShopCategoryView} rows of the viewer's match. */
    public static final String SOURCE_CATEGORIES = "siege.shop-categories";
    /** {@link ShopEntryView} rows of one category ({@code categoryId} param). */
    public static final String SOURCE_ENTRIES = "siege.shop-entries";

    // ---- actions ----
    /** Buys the {@code entryId} param for the clicking player. */
    public static final String ACTION_BUY = "siege.shop.buy";
    /** Opens the shop of the clicking player's match (the Shop button in {@code siege.information}). */
    public static final String ACTION_OPEN = "siege.shop.open";

    // ---- conditions ----
    /** True while the viewer can use a shop: {@code siege.shop.enabled} on and a member of a match with a shop. */
    public static final String CONDITION_AVAILABLE = "siege.shop-available";
}

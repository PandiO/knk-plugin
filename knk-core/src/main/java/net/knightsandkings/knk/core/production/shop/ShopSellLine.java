package net.knightsandkings.knk.core.production.shop;

/**
 * One line of a sale ({@code ShopSellLineDto}): {@code amount} of a blueprint, or one unique item.
 */
public record ShopSellLine(Integer itemBlueprintId, Long itemInstanceId, long amount) {

    public static ShopSellLine blueprint(int blueprintId, long amount) {
        return new ShopSellLine(blueprintId, null, amount);
    }

    public static ShopSellLine instance(long instanceId) {
        return new ShopSellLine(null, instanceId, 1);
    }

    public boolean unique() {
        return itemInstanceId != null;
    }
}

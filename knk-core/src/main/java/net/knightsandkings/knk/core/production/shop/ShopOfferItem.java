package net.knightsandkings.knk.core.production.shop;

import java.util.List;

/**
 * One item of a shop's offer ({@code ShopOfferItemDto}).
 *
 * @param catalogueEntryId null for Merchandise stock without a catalogue entry
 * @param itemInstanceId   a unique item (resold as that exact item), else null
 * @param unitPrice        coins per unit
 * @param priceSource      {@code Static} or {@code Formula}
 * @param stock            units on sale; null is unlimited
 * @param enchantments     a unique item's enchantments, {@code "<name> <level>"}
 */
public record ShopOfferItem(Integer catalogueEntryId, int itemBlueprintId, String itemBlueprintName, Long itemInstanceId,
                            long unitPrice, String priceSource, Long stock, boolean soldOut, boolean pinned, int sortOrder,
                            List<String> enchantments) {

    public ShopOfferItem {
        enchantments = enchantments == null ? List.of() : List.copyOf(enchantments);
    }

    public boolean unique() {
        return itemInstanceId != null;
    }

    public boolean unlimited() {
        return stock == null;
    }

    /** How the menus name this item in their context ({@code ctx.item}). */
    public ShopItemRef ref() {
        return ShopItemRef.of(this);
    }
}

package net.knightsandkings.knk.paper.production.shop;

import java.util.List;

import org.bukkit.inventory.ItemStack;

import net.knightsandkings.knk.core.menu.MenuRowKey;
import net.knightsandkings.knk.core.production.shop.ShopOfferItem;
import net.knightsandkings.knk.core.production.shop.ShopText;
import net.knightsandkings.knk.paper.menu.MenuItemStackRow;
import net.knightsandkings.knk.paper.production.terminal.BlueprintItems;

/**
 * One item of a shop's catalogue (rows of {@code shops.offer} and {@code shops.item}): shown as the item the player gets
 * ({@link BlueprintItems}, the kit builder), with the price and stock lines below its own lore. A sold-out item is greyed
 * out (DESIGN § 7, F5), a pinned one highlighted. An item that can't be built falls back to the row template's material
 * with the item's name.
 */
public final class ShopOfferRow implements MenuItemStackRow, MenuRowKey {

    private final ShopOfferItem item;
    private final BlueprintItems.Template template;

    ShopOfferRow(ShopOfferItem item, BlueprintItems.Template template) {
        this.item = item;
        this.template = template;
    }

    ShopOfferItem offerItem() {
        return item;
    }

    @Override
    public ItemStack menuItemStack() {
        return template == null || item.unique() ? null : template.copy(1);
    }

    /** The context value of this item ({@code ctx.item}). */
    public String getItem() {
        return item.ref().toString();
    }

    public String getName() {
        return "&f" + item.itemBlueprintName();
    }

    /** Used only when the item can't be built (and for unique items, until the instance builder of #897). */
    public String getMaterial() {
        return item.unique() ? "ENCHANTED_BOOK" : "PAPER";
    }

    public List<String> getLines() {
        return ShopText.rowLines(item);
    }

    public String getDisplayMode() {
        if (item.soldOut()) {
            return "DISABLED";
        }
        return item.pinned() ? "HIGHLIGHT" : "NORMAL";
    }

    @Override
    public Object menuRowKey() {
        return List.of(item, template == null ? "none" : "built");
    }
}

package net.knightsandkings.knk.core.production.shop.display;

import java.util.ArrayList;
import java.util.List;

import net.knightsandkings.knk.core.production.shop.ShopOfferItem;
import net.knightsandkings.knk.core.production.shop.ShopText;

/**
 * The floating label over a displayed item and the messages of the stalls (P12, PandiO/knk-workspace#250), with
 * {@code &} colour codes.
 */
public final class DisplayText {

    private DisplayText() {
    }

    /** The label: the item's name, its price (or "Sold out") and the two clicks. */
    public static List<String> label(ShopOfferItem item, boolean greyed, boolean hints) {
        List<String> lines = new ArrayList<>();
        String name = item.itemBlueprintName() == null || item.itemBlueprintName().isBlank()
                ? "Item #" + item.itemBlueprintId() : item.itemBlueprintName();
        lines.add((greyed ? "&7" : "&f") + name);
        if (greyed) {
            lines.add("&cSold out");
        } else {
            lines.add("&6" + ShopText.coins(item.unitPrice()) + (item.unique() ? "" : " &7each"));
        }
        if (hints) {
            lines.add(greyed ? "&8Right-click: information" : "&8Right-click: information · Shift: buy");
        }
        return lines;
    }

    public static String joined(List<String> lines) {
        return String.join("\n", lines);
    }
}

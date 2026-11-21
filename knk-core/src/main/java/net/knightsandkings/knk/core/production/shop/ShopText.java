package net.knightsandkings.knk.core.production.shop;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The words of the shop menus (PandiO/knk-workspace#687), after the legacy V1 shop menus (legacy inventory-menu-screens
 * § 3.7): lines carry {@code &} colour codes, which the menu engine and the sell menu turn into colours. Kept here so
 * they are tested without Bukkit.
 */
public final class ShopText {

    private ShopText() {
    }

    /** {@code 1,234 coins} ({@code 1 coin}). */
    public static String coins(long amount) {
        return String.format(Locale.ROOT, "%,d", amount) + (amount == 1 ? " coin" : " coins");
    }

    public static String units(long amount) {
        return String.format(Locale.ROOT, "%,d", amount);
    }

    /** The Financial item (legacy slot 0): the player's coins, or a wait line before they are loaded. */
    public static String coinsLine(Long coins) {
        return coins == null ? "&7Your coins: &8loading..." : "&7Your coins: &6" + coins(coins);
    }

    /** The shop's info item (legacy ANVIL "Information about X"). */
    public static List<String> shopLines(ShopOffer offer) {
        List<String> lines = new ArrayList<>();
        if (offer == null) {
            lines.add("&8Loading the shop...");
            return lines;
        }
        if (offer.shopTypeName() != null && !offer.shopTypeName().isBlank()) {
            lines.add("&7Type: &f" + offer.shopTypeName());
        }
        lines.add("&7Kind: &f" + kind(offer.kind()));
        lines.add("&7Level: &f" + offer.level());
        lines.add("&7Town: &f" + (offer.townName() == null || offer.townName().isBlank() ? "none" : offer.townName()));
        lines.add(offer.buysFromPlayers() ? "&aBuys items from players" : "&8Doesn't buy from players");
        return lines;
    }

    public static String kind(String kind) {
        if (kind == null || kind.isBlank()) {
            return "Trading";
        }
        return "processing".equalsIgnoreCase(kind) ? "Processing (makes its own goods)" : "Trading";
    }

    /** A catalogue row (legacy: the product + Price, Stock, "Click here for more information"). */
    public static List<String> rowLines(ShopOfferItem item) {
        List<String> lines = new ArrayList<>();
        lines.add("&7Price: &6" + coins(item.unitPrice()) + (item.unique() ? "" : " &7each"));
        lines.add(stockLine(item));
        if (!item.enchantments().isEmpty()) {
            lines.add("&7Enchantments: &f" + String.join(", ", item.enchantments()));
        }
        if (item.unique()) {
            lines.add("&dUnique item");
        }
        if (item.pinned()) {
            lines.add("&eFeatured");
        }
        lines.add(item.soldOut() ? "&cSold out" : "&eClick here for more information");
        return lines;
    }

    public static String stockLine(ShopOfferItem item) {
        if (item.unlimited()) {
            return "&7Stock: &aplenty";
        }
        return item.soldOut() ? "&7Stock: &c0" : "&7Stock: &f" + units(item.stock());
    }

    /** The legacy NAME_TAG "This item costs: N" with stock, total and what a click does. */
    public static List<String> priceLines(ShopOfferItem item, long amount, Long coins) {
        List<String> lines = new ArrayList<>();
        if (item == null) {
            lines.add("&cThis item is no longer for sale here.");
            return lines;
        }
        long total = item.unitPrice() * amount;
        lines.add(stockLine(item));
        lines.add("&7Amount: &f" + units(amount));
        lines.add("&7Total: &6" + coins(total));
        lines.add(item.priceSource() != null && item.priceSource().equalsIgnoreCase("Formula")
                ? "&8Prices may change with the shop's level and stock" : "&8Fixed price");
        lines.add("");
        if (item.soldOut()) {
            lines.add("&cOut of stock");
        } else if (item.unique()) {
            lines.add("&eUnique items can't be bought in game yet (#897)");
        } else if (coins != null && coins < total) {
            lines.add("&cYou need " + coins(total - coins) + " more");
        } else {
            lines.add("&aClick here to buy");
        }
        return lines;
    }

    /** The legacy EMPTY_MAP description item. */
    public static List<String> descriptionLines(ShopOfferItem item) {
        List<String> lines = new ArrayList<>();
        if (item == null) {
            return lines;
        }
        lines.add("&7Item: &f" + item.itemBlueprintName());
        if (item.unique()) {
            lines.add("&7Unique item &8#" + item.itemInstanceId());
        }
        if (!item.enchantments().isEmpty()) {
            for (String enchantment : item.enchantments()) {
                lines.add("&7- &b" + enchantment);
            }
        }
        lines.add("&7Price: " + (item.priceSource() != null && item.priceSource().equalsIgnoreCase("Static")
                ? "&fset by the shop" : "&fthe item's base price, the shop's tax and level"));
        return lines;
    }

    /** The confirmation's BOOK (legacy "Confirmation": "Are you sure ...?"). */
    public static List<String> confirmLines(String itemName, long amount, ShopQuote quote, Long changedFrom, Long coins) {
        List<String> lines = new ArrayList<>();
        if (quote == null) {
            lines.add("&8Asking the shop for the price...");
            return lines;
        }
        lines.add("&7Are you sure you want to buy");
        lines.add("&f" + units(amount) + " x " + (itemName == null ? quote.itemBlueprintName() : itemName) + "&7?");
        lines.add("&7Price: &6" + coins(quote.unitPrice()) + " &7each");
        lines.add("&7Total: &6" + coins(quote.totalPrice()));
        if (changedFrom != null && changedFrom != quote.unitPrice()) {
            lines.add("&eThe price changed from " + coins(changedFrom) + ". Confirm again to buy.");
        }
        if (!quote.buyable()) {
            lines.add("&c" + ShopRefusal.describe(quote.refusal(), quote.refusalMessage()));
        } else if (coins != null && coins < quote.totalPrice()) {
            lines.add("&cYou need " + coins(quote.totalPrice() - coins) + " more");
        }
        return lines;
    }

    /** The purchase reply. */
    public static String bought(long amount, String itemName, long total, boolean inbox, Long coinsAfter) {
        return "&7You bought &f" + units(amount) + " x " + itemName + " &7for &6" + coins(total)
                + (inbox ? "&7. It waits in your reward inbox (/inbox)" : "")
                + (coinsAfter != null ? "&7. You have &6" + coins(coinsAfter) + " &7left." : "&7.");
    }

    /** The sale reply. */
    public static String sold(long total, int lineCount, Long coinsAfter) {
        return "&7You sold " + lineCount + (lineCount == 1 ? " item stack" : " item stacks") + " for &6" + coins(total)
                + (coinsAfter != null ? "&7. You now have &6" + coins(coinsAfter) + "&7." : "&7.");
    }

    /** The sell menu's instructions (legacy BOOK). */
    public static List<String> sellInstructions() {
        return List.of(
                "&7Click items in your inventory to add",
                "&7them to the sale; click them here",
                "&7to take them out again.",
                "&7Then click the chest to sell.",
                "&8Items the shop doesn't buy are marked.");
    }

    /** The sell menu's chest (legacy "Sell items", "Price: N"). */
    public static List<String> sellTotalLines(ShopSellQuote quote, int selected, boolean quoting) {
        List<String> lines = new ArrayList<>();
        if (selected == 0) {
            lines.add("&7Price: &60 coins");
            lines.add("&8Add items first");
            return lines;
        }
        if (quote == null || quoting) {
            lines.add("&7Price: &8asking the shop...");
            return lines;
        }
        lines.add("&7Price: &6" + coins(quote.total()));
        if (!quote.accepted()) {
            lines.add("&cTake out the marked items to sell");
        } else if (!quote.townCanPay()) {
            lines.add("&cThe town can't pay this much right now");
        } else {
            lines.add("&aClick here to sell");
        }
        return lines;
    }

    /** The lines added under a selected item in the sell menu. */
    public static List<String> sellLineLines(ShopSellQuote.Line line, long selectedAmount) {
        List<String> lines = new ArrayList<>();
        if (line == null) {
            lines.add("&8Asking the shop...");
            return lines;
        }
        if (!line.accepted()) {
            lines.add("&c" + ShopRefusal.describe(line.refusal(), line.refusalMessage()));
            lines.add("&8Click to take it out");
            return lines;
        }
        lines.add("&7Buy-back: &6" + coins(line.unitPrice()) + (line.itemInstanceId() == null ? " &7each" : ""));
        lines.add("&7For these " + units(selectedAmount) + ": &6" + coins(line.unitPrice() * selectedAmount));
        lines.add("&8Click to take it out");
        return lines;
    }
}

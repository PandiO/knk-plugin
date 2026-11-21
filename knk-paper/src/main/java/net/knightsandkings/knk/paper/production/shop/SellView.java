package net.knightsandkings.knk.paper.production.shop;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.knightsandkings.knk.core.production.shop.SellBasket;
import net.knightsandkings.knk.core.production.shop.SellMenuLayout;
import net.knightsandkings.knk.core.production.shop.ShopOffer;
import net.knightsandkings.knk.core.production.shop.ShopSellQuote;
import net.knightsandkings.knk.core.production.shop.ShopText;

/**
 * The sell menu of one shop, open for one player (PandiO/knk-workspace#687, shops DESIGN § 3.2 and § 8), in the legacy
 * V1 layout ({@link SellMenuLayout}). The menu engine has no deposit slots (legacy gap G2), so this is a view built in the
 * plugin like the storage terminal: a click on an item in the player's own inventory adds that stack to the basket; the
 * items stay in the player's inventory until the sale, so nothing is lost when the menu closes or the server stops.
 */
public final class SellView implements InventoryHolder {

    private final int shopId;
    private final int userId;
    private final ShopOffer offer;
    private final Inventory inventory;
    private final SellBasket basket = new SellBasket();
    private final List<ItemStack> pickItems = new ArrayList<>();
    private volatile ShopSellQuote quote;
    private volatile boolean quoting;
    private volatile String quoteError;
    private volatile int quoteSeq;
    private volatile boolean busy;

    SellView(int shopId, int userId, ShopOffer offer) {
        this.shopId = shopId;
        this.userId = userId;
        this.offer = offer;
        this.inventory = Bukkit.createInventory(this, SellMenuLayout.SIZE,
                ChatColor.DARK_GRAY + shorten("Sell items to " + (offer == null ? "the shop" : offer.shopName())));
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public int shopId() {
        return shopId;
    }

    public int userId() {
        return userId;
    }

    public ShopOffer offer() {
        return offer;
    }

    public SellBasket basket() {
        return basket;
    }

    public ShopSellQuote quote() {
        return quote;
    }

    boolean busy() {
        return busy;
    }

    void busy(boolean value) {
        busy = value;
    }

    /** Adds a pick with the item it shows; false when it doesn't fit. */
    boolean add(SellBasket.Pick pick, ItemStack shown) {
        if (!basket.add(pick)) {
            return false;
        }
        pickItems.add(shown.clone());
        return true;
    }

    SellBasket.Pick remove(int index) {
        SellBasket.Pick pick = basket.remove(index);
        if (pick != null) {
            pickItems.remove(index);
        }
        return pick;
    }

    void clear() {
        basket.clear();
        pickItems.clear();
        quote = null;
    }

    /** A new quote is on its way; returns its number (an older answer is ignored). */
    int startQuote() {
        quoting = true;
        quoteError = null;
        return ++quoteSeq;
    }

    /** Stores the answer of quote {@code seq}; false when a newer one was started meanwhile. */
    boolean quoted(int seq, ShopSellQuote next, String error) {
        if (seq != quoteSeq) {
            return false;
        }
        quoting = false;
        quote = next;
        quoteError = error;
        return true;
    }

    /** Main thread: draws the menu. */
    void show(Long coins) {
        inventory.clear();
        inventory.setItem(SellMenuLayout.COINS_SLOT, item(Material.GOLD_INGOT, "&6Financial", List.of(ShopText.coinsLine(coins))));
        List<String> total = new ArrayList<>(ShopText.sellTotalLines(quote, basket.size(), quoting));
        if (quoteError != null) {
            total.add("&c" + quoteError);
        }
        inventory.setItem(SellMenuLayout.SELL_SLOT, item(Material.CHEST, "&aSell items", total));
        inventory.setItem(SellMenuLayout.INFO_SLOT, item(Material.ANVIL,
                "&6Information about " + (offer == null ? "this shop" : offer.shopName()), ShopText.shopLines(offer)));
        inventory.setItem(SellMenuLayout.HELP_SLOT, item(Material.BOOK, "&eHow to sell", ShopText.sellInstructions()));
        inventory.setItem(SellMenuLayout.BACK_SLOT, item(Material.BARRIER, "&cBack", List.of("&7Back to the shop")));
        List<SellBasket.Pick> picks = basket.picks();
        for (int i = 0; i < picks.size(); i++) {
            ShopSellQuote current = quoting ? null : quote;
            ShopSellQuote.Line line = current == null ? null : current.line(basket.lineOf(i));
            inventory.setItem(SellMenuLayout.pickSlot(i), withLore(pickItems.get(i), ShopText.sellLineLines(line, picks.get(i).amount())));
        }
    }

    static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ShopService.colour(name));
            List<String> lines = new ArrayList<>();
            for (String line : lore) {
                lines.add(ShopService.colour(line));
            }
            meta.setLore(lines);
            item.setItemMeta(meta);
        }
        return item;
    }

    static ItemStack withLore(ItemStack source, List<String> extra) {
        ItemStack item = source.clone();
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            for (String line : extra) {
                lore.add(ShopService.colour(line));
            }
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    static String shorten(String text) {
        return text.length() > 32 ? text.substring(0, 31) + "..." : text;
    }
}

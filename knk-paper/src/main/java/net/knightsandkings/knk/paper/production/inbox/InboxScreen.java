package net.knightsandkings.knk.paper.production.inbox;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.knightsandkings.knk.core.production.inbox.InboxLayout;
import net.knightsandkings.knk.core.production.inbox.InboxText;
import net.knightsandkings.knk.core.production.inbox.InboxView;
import net.knightsandkings.knk.paper.production.terminal.BlueprintItems;

/**
 * The {@code /inbox} view open for one player (PandiO/knk-workspace#684): a paged virtual inventory of their reward inbox.
 * Every click is cancelled and turned into a claim; one claim at a time ({@link #busy}).
 */
public final class InboxScreen implements InventoryHolder {

    public static final String TITLE = "Reward inbox";

    private final int userId;
    private final Inventory inventory;
    private volatile InboxView view;
    private volatile int page = 1;
    private volatile boolean busy;

    InboxScreen(int userId) {
        this.userId = userId;
        this.inventory = Bukkit.createInventory(this, InboxLayout.SIZE, ChatColor.DARK_GRAY + TITLE);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public int userId() {
        return userId;
    }

    public InboxView view() {
        return view;
    }

    public int page() {
        return page;
    }

    boolean busy() {
        return busy;
    }

    void busy(boolean value) {
        busy = value;
    }

    /** The entry under a top-inventory slot on the current page, or null. */
    public InboxView.Entry entryAt(int slot) {
        int index = InboxLayout.entryIndex(slot);
        InboxView current = view;
        if (index < 0 || current == null) {
            return null;
        }
        List<InboxView.Entry> onPage = InboxLayout.page(current.entries(), page);
        return index < onPage.size() ? onPage.get(index) : null;
    }

    /** Main thread: draws {@code next} on {@code wantedPage} with the items {@code items} built. */
    void show(InboxView next, int wantedPage, BlueprintItems items, Instant now) {
        this.view = next;
        this.page = InboxLayout.clampPage(wantedPage, next.entries().size());
        inventory.clear();
        List<InboxView.Entry> onPage = InboxLayout.page(next.entries(), page);
        for (int i = 0; i < onPage.size(); i++) {
            InboxView.Entry entry = onPage.get(i);
            inventory.setItem(i, entryItem(entry, items.cached(entry.itemBlueprintId()), now));
        }
        int pages = InboxLayout.pageCount(next.entries().size());
        if (page > 1) {
            inventory.setItem(InboxLayout.PREVIOUS_SLOT, button(Material.ARROW, ChatColor.GREEN + "Previous page"));
        }
        if (page < pages) {
            inventory.setItem(InboxLayout.NEXT_SLOT, button(Material.ARROW, ChatColor.GREEN + "Next page"));
        }
        List<String> info = new ArrayList<>();
        info.add(ChatColor.GRAY + "Filled: " + ChatColor.WHITE + InboxText.fill(next.usedUnits(), next.capUnits()));
        if (next.warningPercent() != null) {
            info.add(ChatColor.GOLD + "Nearly full: new rewards beyond the cap are lost");
        }
        if (next.locked()) {
            info.add(ChatColor.RED + "Locked: nothing moves right now");
        }
        if (next.isEmpty()) {
            info.add(ChatColor.GRAY + "Nothing waiting for you");
        }
        info.add(ChatColor.GRAY + "Page " + page + " of " + pages);
        info.add("");
        info.add(ChatColor.YELLOW + "Claim: " + ChatColor.GRAY + "left click a stack, right click one,");
        info.add(ChatColor.GRAY + "shift click as much as fits");
        info.add(ChatColor.GRAY + "Items expire 30 days after they arrive");
        ItemStack chest = button(next.warningPercent() != null ? Material.TRAPPED_CHEST : Material.ENDER_CHEST,
                ChatColor.GOLD + TITLE);
        ItemMeta meta = chest.getItemMeta();
        if (meta != null) {
            meta.setLore(info);
            chest.setItemMeta(meta);
        }
        inventory.setItem(InboxLayout.INFO_SLOT, chest);
    }

    static ItemStack entryItem(InboxView.Entry entry, BlueprintItems.Template template, Instant now) {
        ItemStack item = template != null ? template.copy((int) Math.min(entry.amount(), template.maxStack()))
                : fallback(entry.displayName(), (int) Math.min(entry.amount(), 64));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add(ChatColor.GRAY + "Waiting: " + ChatColor.WHITE + String.format(Locale.ROOT, "%,d", entry.amount()));
            String expiry = InboxText.entryExpiry(entry, now);
            if (expiry != null) {
                lore.add(ChatColor.GOLD + expiry);
            }
            if (entry.unique()) {
                lore.add(ChatColor.DARK_GRAY + "Unique item #" + entry.itemInstanceId());
                lore.add(ChatColor.YELLOW + "Unique items can't be claimed in game yet");
            }
            meta.setLore(lore);
            if (!meta.hasDisplayName()) {
                meta.setDisplayName(ChatColor.WHITE + entry.displayName());
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private static ItemStack fallback(String name, int amount) {
        ItemStack paper = new ItemStack(Material.PAPER, Math.max(1, amount));
        ItemMeta meta = paper.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.WHITE + name);
            paper.setItemMeta(meta);
        }
        return paper;
    }

    static ItemStack button(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            item.setItemMeta(meta);
        }
        return item;
    }
}

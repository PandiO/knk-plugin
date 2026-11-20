package net.knightsandkings.knk.paper.production.terminal;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.knightsandkings.knk.core.production.terminal.StorageContainerInfo;
import net.knightsandkings.knk.core.production.terminal.TerminalLayout;
import net.knightsandkings.knk.core.production.terminal.TerminalPage;

/**
 * A storage terminal open for one player (production DESIGN § 6.2 Terminal mode): a paged virtual inventory of the
 * storage. The block's own inventory stays empty; every click is cancelled and turned into a ledger move. One move at a
 * time per view ({@link #busy}).
 */
public final class TerminalView implements InventoryHolder {

    private final StorageContainerInfo container;
    private final Inventory inventory;
    private volatile TerminalPage page;
    private volatile boolean busy;

    TerminalView(StorageContainerInfo container) {
        this.container = container;
        this.inventory = Bukkit.createInventory(this, TerminalLayout.SIZE,
                ChatColor.DARK_GRAY + "Terminal: " + shorten(container.displayName()));
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public StorageContainerInfo container() {
        return container;
    }

    public TerminalPage page() {
        return page;
    }

    public int pageNumber() {
        return page == null ? 1 : page.page();
    }

    boolean busy() {
        return busy;
    }

    void busy(boolean value) {
        busy = value;
    }

    /** The entry under a top-inventory slot, or null. */
    public TerminalPage.Entry entryAt(int slot) {
        int index = TerminalLayout.entryIndex(slot);
        TerminalPage current = page;
        if (index < 0 || current == null || index >= current.entries().size()) {
            return null;
        }
        return current.entries().get(index);
    }

    /** Main thread: draws {@code next} with the items {@code items} built. */
    void show(TerminalPage next, BlueprintItems items) {
        this.page = next;
        inventory.clear();
        List<TerminalPage.Entry> entries = next.entries();
        for (int i = 0; i < entries.size() && i < TerminalLayout.ENTRIES_PER_PAGE; i++) {
            inventory.setItem(i, entryItem(entries.get(i), items.cached(entries.get(i).itemBlueprintId())));
        }
        if (next.hasPrevious()) {
            inventory.setItem(TerminalLayout.PREVIOUS_SLOT, button(Material.ARROW, ChatColor.GREEN + "Previous page"));
        }
        if (next.hasNext()) {
            inventory.setItem(TerminalLayout.NEXT_SLOT, button(Material.ARROW, ChatColor.GREEN + "Next page"));
        }
        List<String> info = new ArrayList<>();
        info.add(ChatColor.GRAY + TerminalLayout.fillText(next.totalUnits(), next.maxUnits()));
        info.add(ChatColor.GRAY + TerminalLayout.pageText(next));
        if (next.locked()) {
            info.add(ChatColor.RED + "Locked: nothing moves right now");
        }
        info.add("");
        info.add(ChatColor.YELLOW + "Take: " + ChatColor.GRAY + "left click a stack, right click one,");
        info.add(ChatColor.GRAY + "shift click as much as fits");
        info.add(ChatColor.YELLOW + "Put: " + ChatColor.GRAY + "click an item in your inventory");
        info.add(ChatColor.GRAY + "(right click puts one)");
        ItemStack chest = button(Material.CHEST, ChatColor.GOLD + shorten(next.storageName() != null ? next.storageName()
                : container.displayName()));
        ItemMeta meta = chest.getItemMeta();
        if (meta != null) {
            meta.setLore(info);
            chest.setItemMeta(meta);
        }
        inventory.setItem(TerminalLayout.INFO_SLOT, chest);
    }

    static ItemStack entryItem(TerminalPage.Entry entry, BlueprintItems.Template template) {
        ItemStack item = template != null ? template.copy((int) Math.min(entry.amount(), template.maxStack()))
                : BlueprintItems.fallback(entry.name()).copy((int) Math.min(entry.amount(), 64));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add(ChatColor.GRAY + "Amount: " + ChatColor.WHITE + String.format(java.util.Locale.ROOT, "%,d", entry.amount()));
            if (entry.unique()) {
                lore.add(ChatColor.DARK_GRAY + "Unique item #" + entry.itemInstanceId());
                lore.add(ChatColor.YELLOW + "Unique items move through the web app for now");
            }
            meta.setLore(lore);
            if (!meta.hasDisplayName()) {
                meta.setDisplayName(ChatColor.WHITE + entry.name());
            }
            item.setItemMeta(meta);
        }
        return item;
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

    static String shorten(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 28 ? text.substring(0, 27) + "..." : text;
    }
}

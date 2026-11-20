package net.knightsandkings.knk.paper.production.terminal;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import net.knightsandkings.knk.core.production.terminal.StorageContainerInfo;

/**
 * What a player sees when opening a display container (DESIGN § 6.2 Display mode): a read-only copy of its sample. Every
 * click and drag is cancelled; the block's own inventory is never opened, so nothing can be taken from it.
 */
public final class DisplayView implements InventoryHolder {

    private final StorageContainerInfo container;
    private final Inventory inventory;

    DisplayView(StorageContainerInfo container, ItemStack[] contents) {
        this.container = container;
        int size = Math.max(9, Math.min(54, ((contents.length + 8) / 9) * 9));
        this.inventory = Bukkit.createInventory(this, size,
                ChatColor.DARK_GRAY + "Display: " + TerminalView.shorten(container.displayName()));
        for (int i = 0; i < contents.length && i < size; i++) {
            if (contents[i] != null) {
                inventory.setItem(i, contents[i].clone());
            }
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public StorageContainerInfo container() {
        return container;
    }
}

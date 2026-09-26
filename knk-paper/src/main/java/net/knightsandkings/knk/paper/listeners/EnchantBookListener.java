package net.knightsandkings.knk.paper.listeners;

import net.knightsandkings.knk.paper.enchantbook.EnchantBookItems;
import net.knightsandkings.knk.paper.enchantbook.EnchantBookMenu;
import net.knightsandkings.knk.paper.enchantbook.EnchantBooks;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.Plugin;

/**
 * Permanent enchantment books (docs/specs/enchantment-books/ENCHANTMENT_BOOK_APPLICATION.md §3.2; Linear KNG-5):
 * right-click the book in hand to pick an item from a chooser ({@link EnchantBookMenu}). That is the only way
 * to apply one - clicking a book from the cursor onto an item was removed after the 2026-09-26 manual test as
 * too accident-prone, so a book on the cursor now behaves like any other item. Only reacts to items tagged
 * {@link EnchantBookItems#BOOK_KEY}, so siege books are left to the siege listener.
 */
public class EnchantBookListener implements Listener {

    private final EnchantBookMenu menu;

    public EnchantBookListener(EnchantBooks books, Plugin plugin) {
        this.menu = new EnchantBookMenu(books, plugin);
    }

    /** Right-click with a book in hand opens the item chooser. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() == null || !EnchantBookItems.isBook(event.getItem())) return;
        event.setCancelled(true);
        Component problem = menu.open(event.getPlayer(), event.getHand());
        if (problem != null) event.getPlayer().sendActionBar(problem);
    }

    /** Every click in the chooser or its confirmation is cancelled; clicks on their own slots are handled. */
    @EventHandler(priority = EventPriority.LOW)
    public void onMenuClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder(false);
        if (!EnchantBookMenu.isMenu(holder)) return;
        event.setCancelled(true);
        if (event.getWhoClicked() instanceof Player player && event.getClickedInventory() == event.getView().getTopInventory()) {
            menu.click(player, holder, event.getSlot());
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onMenuDrag(InventoryDragEvent event) {
        if (EnchantBookMenu.isMenu(event.getView().getTopInventory().getHolder(false))) event.setCancelled(true);
    }
}

package net.knightsandkings.knk.paper.listeners;

import net.knightsandkings.knk.core.enchantbook.EnchantBookRules.ApplyResult;
import net.knightsandkings.knk.paper.enchantbook.EnchantBookItems;
import net.knightsandkings.knk.paper.enchantbook.EnchantBookMenu;
import net.knightsandkings.knk.paper.enchantbook.EnchantBooks;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;

/**
 * Permanent enchantment books in the inventory (docs/specs/enchantment-books/ENCHANTMENT_BOOK_APPLICATION.md
 * §3.2; Linear KNG-5), ported from {@code SiegeEnchantBookListener} without the siege pickup rules: right-click
 * the book in hand to pick an item from a chooser ({@link EnchantBookMenu}), or click the book held on the
 * cursor onto an item in your own inventory (survival and creative inventories alike). Only reacts to items
 * tagged {@link EnchantBookItems#BOOK_KEY}, so siege books are left to the siege listener.
 */
public class EnchantBookListener implements Listener {

    private final EnchantBooks books;
    private final EnchantBookMenu menu;
    private final Plugin plugin;

    public EnchantBookListener(EnchantBooks books, Plugin plugin) {
        this.books = books;
        this.menu = new EnchantBookMenu(books);
        this.plugin = plugin;
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

    /** Every click in the chooser is cancelled; a click on a shown item applies the book. */
    @EventHandler(priority = EventPriority.LOW)
    public void onChooserClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof EnchantBookMenu.Holder holder)) return;
        event.setCancelled(true);
        if (event.getWhoClicked() instanceof Player player && event.getClickedInventory() == event.getView().getTopInventory()) {
            menu.click(player, holder, event.getSlot());
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onChooserDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof EnchantBookMenu.Holder) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onApply(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event instanceof InventoryCreativeEvent creative) {
            onCreativeApply(player, creative);
            return;
        }
        ItemStack cursor = event.getCursor();
        if (!EnchantBookItems.isBook(cursor)) return;
        if (!(event.getClickedInventory() instanceof PlayerInventory)) return;
        if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) return;
        ItemStack target = event.getCurrentItem();
        if (target == null || target.getType().isAir()) return; // putting the book down is fine
        if (EnchantBookItems.isBook(target)) return; // stacking/swapping books is fine

        event.setCancelled(true);
        EnchantBooks.Outcome outcome = books.apply(cursor, target);
        if (outcome.result() == ApplyResult.APPLIED) {
            event.setCurrentItem(outcome.updated());
            ItemStack rest = cursor.clone();
            rest.setAmount(cursor.getAmount() - 1);
            event.getView().setCursor(rest.getAmount() > 0 ? rest : null);
            player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1f);
        }
        player.sendActionBar(EnchantBookMenu.message(outcome));
    }

    /**
     * Creative mode. The creative inventory keeps the cursor on the client and only tells the server "slot N now
     * holds X" ({@link InventoryCreativeEvent}, {@code ClickType.CREATIVE}; {@code getCursor()} is X, the item being
     * put down). So clicking a book onto an item arrives as "slot N = book", never as the LEFT/RIGHT click
     * {@link #onApply} handles above - which is why cursor-apply did nothing in creative (KNG-5 manual test,
     * 2026-09-26).
     * <p>
     * Cancelling makes the server resend slot N (the item stays) and clear the client's cursor. A tick later the
     * enchanted item is written into the slot and what is left of the book goes back on the cursor; a refused
     * book goes back on the cursor whole, as if the click never happened.
     */
    private void onCreativeApply(Player player, InventoryCreativeEvent event) {
        ItemStack book = event.getCursor();
        if (!EnchantBookItems.isBook(book)) return;
        if (!(event.getClickedInventory() instanceof PlayerInventory inventory)) return;
        ItemStack target = event.getCurrentItem();
        if (target == null || target.getType().isAir()) return; // putting the book down is fine
        if (EnchantBookItems.isBook(target)) return;

        event.setCancelled(true);
        int slot = event.getSlot();
        ItemStack bookCopy = book.clone();
        ItemStack targetCopy = target.clone();
        EnchantBooks.Outcome outcome = books.apply(bookCopy, targetCopy);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            // Only if the slot still holds the item we evaluated (the client could have moved it meanwhile).
            boolean applied = outcome.result() == ApplyResult.APPLIED && targetCopy.equals(inventory.getItem(slot));
            int bookLeft = bookCopy.getAmount();
            if (applied) {
                inventory.setItem(slot, outcome.updated());
                bookLeft--;
                player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1f);
            }
            if (bookLeft > 0) {
                bookCopy.setAmount(bookLeft);
                player.setItemOnCursor(bookCopy);
            } else {
                player.setItemOnCursor(null);
            }
            player.updateInventory();
            player.sendActionBar(outcome.result() == ApplyResult.APPLIED && !applied
                    ? Component.text("The item moved - try again.", NamedTextColor.RED)
                    : EnchantBookMenu.message(outcome));
        });
    }
}

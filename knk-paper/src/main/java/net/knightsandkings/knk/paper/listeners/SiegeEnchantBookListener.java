package net.knightsandkings.knk.paper.listeners;

import net.knightsandkings.knk.core.siege.SiegeEnchantMarkers;
import net.knightsandkings.knk.paper.siege.SiegeEnchantBooks;
import net.knightsandkings.knk.paper.siege.SiegeEnchantBooks.ApplyResult;
import net.knightsandkings.knk.paper.siege.SiegeEnchantMenu;
import net.knightsandkings.knk.paper.siege.SiegeMessages;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Siege enchantment books in the world and in the inventory (DESIGN §9.3–9.4, siege Phase 5c).
 * <ul>
 *   <li><b>Pickup</b> ({@code HIGHEST}, after {@code PlayerListener.onItemPickup} and the siege inventory
 *       guard): a siege book is picked up only by a member of the match it dropped in - un-cancelled
 *       for them, cancelled for everyone else. Mobs, allays and hoppers never take one.</li>
 *   <li><b>Applying:</b> right-click the book in hand to pick an item from a chooser ({@link SiegeEnchantMenu}),
 *       or click the book held on the cursor onto an item in your own inventory ({@link SiegeEnchantBooks#apply});
 *       a book of another (or no) running match is removed.</li>
 * </ul>
 */
public class SiegeEnchantBookListener implements Listener {

    private final SiegeEnchantBooks books;
    private final SiegeEnchantMenu menu;

    public SiegeEnchantBookListener(SiegeEnchantBooks books) {
        this.books = books;
        this.menu = new SiegeEnchantMenu(books);
    }

    /** Right-click with a siege book in hand opens the item chooser (v2 PlayerEnchantMenu behaviour). */
    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() == null || books.bookToken(event.getItem()) == null) return;
        event.setCancelled(true);
        Component problem = menu.open(event.getPlayer(), event.getHand());
        if (problem != null) event.getPlayer().sendActionBar(problem);
    }

    /** Every click in the chooser is cancelled; a click on a shown item applies the book. */
    @EventHandler(priority = EventPriority.LOW)
    public void onChooserClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof SiegeEnchantMenu.Holder holder)) return;
        event.setCancelled(true);
        if (event.getWhoClicked() instanceof Player player && event.getClickedInventory() == event.getView().getTopInventory()) {
            menu.click(player, holder, event.getSlot());
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onChooserDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof SiegeEnchantMenu.Holder) event.setCancelled(true);
    }

    @SuppressWarnings("deprecation") // the same event PlayerListener.onItemPickup cancels
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerPickup(PlayerPickupItemEvent event) {
        String token = books.bookToken(event.getItem().getItemStack());
        if (token == null) return;
        event.setCancelled(!SiegeEnchantMarkers.mayPickUp(token, books.runningTokenOf(event.getPlayer())));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player) return;
        if (books.bookToken(event.getItem().getItemStack()) != null) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHopperPickup(InventoryPickupItemEvent event) {
        if (books.bookToken(event.getItem().getItemStack()) != null) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onApply(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack cursor = event.getCursor();
        if (books.bookToken(cursor) == null) return;
        if (!(event.getClickedInventory() instanceof PlayerInventory)) return;
        if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) return;
        ItemStack target = event.getCurrentItem();
        if (target == null || target.getType().isAir()) return; // putting the book down is fine

        event.setCancelled(true);
        ItemStack[] updated = new ItemStack[1];
        ApplyResult result = books.apply(player, cursor, target, updated);
        switch (result) {
            case APPLIED -> {
                event.setCurrentItem(updated[0]);
                ItemStack rest = cursor.clone();
                rest.setAmount(cursor.getAmount() - 1);
                event.getView().setCursor(rest.getAmount() > 0 ? rest : null);
            }
            case NOT_IN_MATCH, WRONG_MATCH -> event.getView().setCursor(null);
            default -> { }
        }
        player.sendActionBar(Component.text(SiegeEnchantBooks.describe(result),
                result == ApplyResult.APPLIED ? SiegeMessages.GOOD : SiegeMessages.BAD));
    }
}

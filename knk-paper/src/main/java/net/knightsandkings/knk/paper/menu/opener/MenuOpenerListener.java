package net.knightsandkings.knk.paper.menu.opener;

import java.util.Objects;
import java.util.function.Predicate;

import org.bukkit.block.Block;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import net.knightsandkings.knk.core.menu.opener.MenuOpenerSettings;
import net.knightsandkings.knk.core.menu.opener.MenuOpenerSettings.Click;

/**
 * The menu item's events (PandiO/knk-workspace#160):
 * <ul>
 *   <li><b>Given</b> a tick after every join ({@code give-on-join}) and respawn ({@code keep-on-death}); the join pass
 *       also removes copies and the item of a player who should not have it.</li>
 *   <li><b>Opens the menu</b> on a right-click (in the air, or on a block that does nothing on its own, or any block
 *       while sneaking), a left-click, or a click on it in the player's own inventory - as {@code open-on} says.</li>
 *   <li><b>Stays a pointer</b>: never on the ground (death drops, item entities), never in another inventory
 *       (containers, the crafting grid, item frames, armour stands), never placed as a block; not dropped or moved
 *       unless {@code droppable} / {@code movable}. Creative-mode inventory clicks are left alone (the client owns
 *       them); the next join removes any copy they made.</li>
 * </ul>
 */
public final class MenuOpenerListener implements Listener {

    private final MenuOpenerService service;
    private final Predicate<Inventory> ownInventoryView;
    private final Predicate<Block> interactsOnItsOwn;

    public MenuOpenerListener(MenuOpenerService service) {
        this(service, MenuOpenerListener::isOwnInventoryView, MenuOpenerListener::interactsOnItsOwn);
    }

    /** Tests: inventory types and block materials need a running server. */
    MenuOpenerListener(MenuOpenerService service, Predicate<Inventory> ownInventoryView, Predicate<Block> interactsOnItsOwn) {
        this.service = Objects.requireNonNull(service, "service");
        this.ownInventoryView = Objects.requireNonNull(ownInventoryView, "ownInventoryView");
        this.interactsOnItsOwn = Objects.requireNonNull(interactsOnItsOwn, "interactsOnItsOwn");
    }

    // ==================== given ====================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        service.ensureLater(event.getPlayer(), service.settings().giveOnJoin());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        service.ensureLater(event.getPlayer(), service.settings().keepOnDeath());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(service::isOpener);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        service.forget(event.getPlayer().getUniqueId());
    }

    // ==================== clicks ====================

    /**
     * Not {@code ignoreCancelled}: Bukkit reports a click in the air as cancelled (no block to use), as for the
     * navigation compass. A use of the item somebody denied on purpose is left alone.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !service.isOpener(event.getItem())) {
            return;
        }
        if (event.useItemInHand() == Event.Result.DENY) {
            return;
        }
        Action action = event.getAction();
        MenuOpenerSettings s = service.settings();
        Player player = event.getPlayer();
        boolean right = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
        boolean left = action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK;
        if (right) {
            // never "use" the item itself (a configured ender pearl or bow must not fire)
            event.setUseItemInHand(Event.Result.DENY);
            if (action == Action.RIGHT_CLICK_BLOCK && !player.isSneaking() && interactsOnItsOwn.test(event.getClickedBlock())) {
                return; // a door, chest or button keeps working with the item in hand
            }
            if (s.opensOn(Click.RIGHT_CLICK)) {
                service.open(player, false);
            }
        } else if (left && s.opensOn(Click.LEFT_CLICK)) {
            event.setUseItemInHand(Event.Result.DENY);
            service.open(player, false);
        }
    }

    private static boolean interactsOnItsOwn(Block block) {
        return block != null && block.getType().isInteractable();
    }

    // ==================== inventories ====================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event instanceof InventoryCreativeEvent || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack current = event.getCurrentItem();
        ItemStack hotbar = event.getClick() == ClickType.NUMBER_KEY && event.getHotbarButton() >= 0
            ? player.getInventory().getItem(event.getHotbarButton()) : null;
        ItemStack offhand = event.getClick() == ClickType.SWAP_OFFHAND ? player.getInventory().getItemInOffHand() : null;
        boolean onItem = service.isOpener(current);
        if (!onItem && !service.isOpener(event.getCursor()) && !service.isOpener(hotbar) && !service.isOpener(offhand)) {
            return;
        }
        MenuOpenerSettings s = service.settings();
        Inventory top = event.getView().getTopInventory();
        boolean ownView = ownInventoryView.test(top);
        boolean inTop = event.getClickedInventory() != null && event.getClickedInventory().equals(top);
        if (s.movable() && ownView && !inTop) {
            return; // moving it about in the player's own inventory
        }
        event.setCancelled(true);
        if (!s.movable() && ownView && !inTop && onItem && s.opensOn(Click.INVENTORY_CLICK)) {
            service.open(player, true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!service.isOpener(event.getOldCursor())) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        boolean intoTop = event.getRawSlots().stream().anyMatch(raw -> raw < top.getSize());
        if (!service.settings().movable() || intoTop || !ownInventoryView.test(top)) {
            event.setCancelled(true);
        }
    }

    /** The player's own inventory screen (its top part is the 2x2 crafting grid), not a chest or another GUI. */
    private static boolean isOwnInventoryView(Inventory top) {
        return top == null || top.getType() == InventoryType.CRAFTING || top.getType() == InventoryType.CREATIVE;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (!service.settings().movable()
            && (service.isOpener(event.getMainHandItem()) || service.isOpener(event.getOffHandItem()))) {
            event.setCancelled(true);
        }
    }

    // ==================== never a game item ====================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Item dropped = event.getItemDrop();
        if (!service.isOpener(dropped.getItemStack())) {
            return;
        }
        if (service.settings().droppable()) {
            dropped.remove(); // out of the inventory, never on the ground: back at the next join or respawn
        } else {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (service.isOpener(event.getItemInHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof Hanging
            && service.isOpener(event.getPlayer().getInventory().getItem(event.getHand()))) {
            event.setCancelled(true); // item frames
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (service.isOpener(event.getPlayerItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (service.isOpener(event.getEntity().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (service.isOpener(event.getItem().getItemStack())) {
            event.setCancelled(true);
            event.getItem().remove();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHopperPickup(InventoryPickupItemEvent event) {
        if (service.isOpener(event.getItem().getItemStack())) {
            event.setCancelled(true);
            event.getItem().remove();
        }
    }
}

package net.knightsandkings.knk.paper.listeners;

import net.knightsandkings.knk.paper.siege.SiegeMessages;
import net.knightsandkings.knk.paper.siege.SiegeService;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Allay;
import org.bukkit.entity.ChestedHorse;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.EnumSet;
import java.util.Set;

/**
 * Duplication guards (DESIGN §9.3), required by own-gear + restore (D2): while a member is away in a
 * match (HUB/IN_PROGRESS) nothing may leave or enter their inventory through the world, or the restore
 * would hand it back a second time (or wipe what came in). Denied:
 * <ul>
 *   <li>dropping items;</li>
 *   <li>opening any world storage (container blocks, ender chest, storage entities such as chest
 *       minecarts/boats/donkeys, merchants and crafters) - InventoryMenu GUIs are not world storage and
 *       stay usable; clicks/drags into such an inventory are denied too, in case one was already open;</li>
 *   <li>item frames, armour stands, allays and chest-on-donkey interactions;</li>
 *   <li>placing storage blocks (containers, shulker boxes, ender chests), item-holding blocks
 *       (lecterns, jukeboxes, chiseled bookshelves, decorated pots, campfires, composters, flower pots)
 *       and placeable entities/hangings (minecarts, boats, armour stands, item frames, paintings);</li>
 *   <li>item pickup, for OPs as well ({@code PlayerListener.onItemPickup} already blocks non-OPs) -
 *       except siege enchantment books of the member's own match, which the 5c book listener
 *       un-cancels at {@code HIGHEST}.</li>
 * </ul>
 */
public class SiegeInventoryGuardListener implements Listener {

    private static final Set<Material> ITEM_HOLDING_BLOCKS = EnumSet.of(
            Material.LECTERN, Material.JUKEBOX, Material.CHISELED_BOOKSHELF, Material.DECORATED_POT,
            Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.COMPOSTER, Material.ENDER_CHEST);

    private final SiegeService service;

    public SiegeInventoryGuardListener(SiegeService service) {
        this.service = service;
    }

    private boolean guarded(HumanEntity entity) {
        return entity instanceof Player player && service.activeLobbyOf(player.getUniqueId()).isPresent();
    }

    private static void tell(HumanEntity entity, String message) {
        if (entity instanceof Player p) p.sendActionBar(Component.text(message, SiegeMessages.BAD));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!guarded(event.getPlayer())) return;
        event.setCancelled(true);
        tell(event.getPlayer(), "You can't drop items during a siege.");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!guarded(event.getPlayer()) || !isWorldStorage(event.getInventory())) return;
        event.setCancelled(true);
        tell(event.getPlayer(), "You can't use storage during a siege; your inventory is restored afterwards.");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!guarded(event.getWhoClicked())) return;
        if (isWorldStorage(event.getView().getTopInventory())) {
            event.setCancelled(true);
            event.getWhoClicked().closeInventory();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (guarded(event.getWhoClicked()) && isWorldStorage(event.getView().getTopInventory())) {
            event.setCancelled(true);
        }
    }

    /** Persistent world storage: anything whose contents outlive the player's restore. */
    static boolean isWorldStorage(Inventory inventory) {
        if (inventory == null) return false;
        InventoryType type = inventory.getType();
        if (type == InventoryType.ENDER_CHEST || type == InventoryType.MERCHANT || type == InventoryType.CRAFTER) return true;
        if (type == InventoryType.PLAYER || type == InventoryType.CRAFTING) return false;
        InventoryHolder holder = inventory.getHolder(false);
        return holder instanceof BlockInventoryHolder || holder instanceof DoubleChest
                || (holder instanceof Entity && !(holder instanceof HumanEntity));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (!guarded(event.getPlayer())) return;
        Entity target = event.getRightClicked();
        boolean chestOnDonkey = target instanceof ChestedHorse
                && event.getPlayer().getInventory().getItem(event.getHand()).getType() == Material.CHEST;
        if (target instanceof ItemFrame || target instanceof Allay || chestOnDonkey) {
            event.setCancelled(true);
            tell(event.getPlayer(), "You can't do that during a siege.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (!guarded(event.getPlayer())) return;
        event.setCancelled(true);
        tell(event.getPlayer(), "You can't do that during a siege.");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractBlock(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || !guarded(event.getPlayer())) return;
        Block block = event.getClickedBlock();
        if (block == null) return;
        Material type = block.getType();
        if (ITEM_HOLDING_BLOCKS.contains(type) || Tag.FLOWER_POTS.isTagged(type)) {
            event.setUseInteractedBlock(Event.Result.DENY);
            if (event.getItem() != null && isStoragePlaceable(event.getItem().getType())) {
                event.setUseItemInHand(Event.Result.DENY);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!guarded(event.getPlayer())) return;
        Material type = event.getBlockPlaced().getType();
        if (isStoragePlaceable(type) || event.getBlockPlaced().getState() instanceof BlockInventoryHolder) {
            event.setCancelled(true);
            tell(event.getPlayer(), "You can't place storage blocks during a siege.");
        }
    }

    private static boolean isStoragePlaceable(Material type) {
        return Tag.SHULKER_BOXES.isTagged(type) || ITEM_HOLDING_BLOCKS.contains(type) || Tag.FLOWER_POTS.isTagged(type)
                || type == Material.CHEST || type == Material.TRAPPED_CHEST || type == Material.BARREL
                || type == Material.HOPPER || type == Material.DROPPER || type == Material.DISPENSER
                || type == Material.CRAFTER || type == Material.FURNACE || type == Material.BLAST_FURNACE
                || type == Material.SMOKER || type == Material.BREWING_STAND;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        if (event.getPlayer() == null || !guarded(event.getPlayer())) return;
        event.setCancelled(true);
        tell(event.getPlayer(), "You can't place that during a siege.");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (event.getPlayer() == null || !guarded(event.getPlayer())) return;
        event.setCancelled(true);
        tell(event.getPlayer(), "You can't place that during a siege.");
    }

    /**
     * DESIGN §9.3: members pick up nothing during a match, OPs included. Siege books of the member's
     * own match are un-cancelled afterwards by the 5c book listener at {@code HIGHEST}.
     */
    @SuppressWarnings("deprecation") // PlayerListener uses the same event; the book listener must see the same one
    @EventHandler(priority = EventPriority.HIGH)
    public void onPickup(PlayerPickupItemEvent event) {
        if (guarded(event.getPlayer())) event.setCancelled(true);
    }
}

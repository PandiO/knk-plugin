package net.knightsandkings.knk.paper.items.identity;

import io.papermc.paper.event.player.PlayerPurchaseEvent;
import net.knightsandkings.knk.core.items.identity.ItemConversionLog.Source;
import org.bukkit.Location;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The conversion hooks of production DESIGN §8 (PandiO/knk-workspace#141), registered only with
 * {@code items.identity.convert-on-obtain: true}: every item a player obtains goes through
 * {@link ItemIdentityService#convert} (resolve, retag rule, count). Each handler only reads the in-memory map; none
 * calls the API. Plain vanilla items are never changed, so with the canonical-form rule the only change a hook makes
 * is removing a stale blueprint tag from an otherwise plain item.
 * <ol>
 *   <li>{@link EntityPickupItemEvent} (players only)</li>
 *   <li>{@link BlockDropItemEvent}</li>
 *   <li>{@link EntityDeathEvent} drops</li>
 *   <li>{@link PlayerFishEvent} (a caught item)</li>
 *   <li>{@link PrepareItemCraftEvent} (the result shown) and {@link CraftItemEvent} (counted)</li>
 *   <li>{@link PlayerPurchaseEvent}: villager and wandering trader trades (counted)</li>
 *   <li>{@link InventoryOpenEvent}: the first open of a container block since the server started (no world data
 *       is written to remember it)</li>
 * </ol>
 */
public final class ItemConversionListener implements Listener {

    /** Bounds the memory of opened containers; cleared when full (a container is then scanned once more). */
    static final int MAX_REMEMBERED_CONTAINERS = 50_000;

    private final ItemIdentityService service;
    private final Set<String> scannedContainers = ConcurrentHashMap.newKeySet();

    public ItemConversionListener(ItemIdentityService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player) {
            convertEntity(event.getItem(), Source.PICKUP);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockDrop(BlockDropItemEvent event) {
        for (Item item : event.getItems()) {
            convertEntity(item, Source.BLOCK_DROP);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        for (ItemStack drop : event.getDrops()) {
            if (drop != null) {
                service.convert(drop, Source.ENTITY_DEATH); // the list's own stacks: changed in place
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH && event.getCaught() instanceof Item item) {
            convertEntity(item, Source.FISHING);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        ItemStack result = event.getInventory().getResult();
        if (result != null && !result.getType().isAir()) {
            ItemStack copy = result.clone();
            if (service.convertWithoutCounting(copy)) {
                event.getInventory().setResult(copy);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        ItemStack result = event.getInventory().getResult();
        if (result != null && !result.getType().isAir()) {
            service.convert(result.clone(), Source.CRAFT); // counted; the result itself was normalised when prepared
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrade(PlayerPurchaseEvent event) {
        ItemStack result = event.getTrade() != null ? event.getTrade().getResult() : null;
        if (result != null && !result.getType().isAir()) {
            service.convert(result.clone(), Source.TRADE); // vanilla trade results are plain: counted, not changed
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onContainerOpen(InventoryOpenEvent event) {
        Inventory inventory = event.getInventory();
        InventoryHolder holder = inventory.getHolder(false);
        if (!(holder instanceof Container) && !(holder instanceof DoubleChest)) {
            return;
        }
        Location location = inventory.getLocation();
        if (location == null || location.getWorld() == null) {
            return;
        }
        String key = location.getWorld().getName() + ':' + location.getBlockX() + ',' + location.getBlockY() + ',' + location.getBlockZ();
        if (scannedContainers.size() >= MAX_REMEMBERED_CONTAINERS) {
            scannedContainers.clear();
        }
        if (!scannedContainers.add(key)) {
            return;
        }
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item != null && !item.getType().isAir() && service.convert(item, Source.CONTAINER_OPEN)) {
                inventory.setItem(slot, item);
            }
        }
    }

    private void convertEntity(Item entity, Source source) {
        ItemStack stack = entity.getItemStack();
        if (service.convert(stack, source)) {
            entity.setItemStack(stack);
        }
    }

    /** How many containers are remembered (tests). */
    int rememberedContainers() {
        return scannedContainers.size();
    }
}

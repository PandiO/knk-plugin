package net.knightsandkings.knk.paper.listeners;

import net.knightsandkings.knk.paper.duel.DuelService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.entity.Allay;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.inventory.BlockInventoryHolder;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * Duel guards (arena duels DESIGN §5.1, §8.3, §8.4), from LOCKING until the duelist's gear is back:
 * <ul>
 *   <li>no dropping items, no world storage (containers, ender chest, storage entities, merchants), no item frames,
 *       armour stands or allays, no placing storage blocks or placeable entities, no picking items up - a duelist
 *       can't pass items to an accomplice, and nothing slips past the kit restore or into the spoils;</li>
 *   <li>kit modes in WARMUP: no kit consumables (eating, drinking, throwing), so the kit is whole at the fight;</li>
 *   <li>the countdown: no projectiles or pearls, and the duelist is frozen to their spawn block (looking around is
 *       fine).</li>
 * </ul>
 * Players in owner or staff mode are exempt, as in the siege guards.
 */
public class DuelInventoryGuardListener implements Listener {

    private final DuelService service;
    private final Predicate<Player> exempt;

    public DuelInventoryGuardListener(DuelService service, Predicate<Player> exempt) {
        this.service = service;
        this.exempt = exempt == null ? p -> false : exempt;
    }

    private boolean guarded(HumanEntity entity) {
        return entity instanceof Player player && service.isParticipating(player.getUniqueId()) && !exempt.test(player);
    }

    private static void tell(HumanEntity entity, String message) {
        if (entity instanceof Player p) p.sendActionBar(Component.text(message, NamedTextColor.RED));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!guarded(event.getPlayer())) return;
        event.setCancelled(true);
        tell(event.getPlayer(), "You can't drop items during a duel.");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!guarded(event.getPlayer()) || !SiegeInventoryGuardListener.isWorldStorage(event.getInventory())) return;
        event.setCancelled(true);
        tell(event.getPlayer(), "You can't use storage during a duel.");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (guarded(event.getWhoClicked()) && SiegeInventoryGuardListener.isWorldStorage(event.getView().getTopInventory())) {
            event.setCancelled(true);
            event.getWhoClicked().closeInventory();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (guarded(event.getWhoClicked()) && SiegeInventoryGuardListener.isWorldStorage(event.getView().getTopInventory())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (!guarded(event.getPlayer())) return;
        if (event.getRightClicked() instanceof ItemFrame || event.getRightClicked() instanceof Allay) {
            event.setCancelled(true);
            tell(event.getPlayer(), "You can't do that during a duel.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (!guarded(event.getPlayer())) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!guarded(event.getPlayer())) return;
        if (event.getBlockPlaced().getState() instanceof BlockInventoryHolder) {
            event.setCancelled(true);
            tell(event.getPlayer(), "You can't place storage blocks during a duel.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        if (event.getPlayer() == null || !guarded(event.getPlayer())) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (event.getPlayer() == null || !guarded(event.getPlayer())) return;
        event.setCancelled(true);
    }

    @SuppressWarnings("deprecation") // the siege book listener uses the same event
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(PlayerPickupItemEvent event) {
        if (guarded(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (exempt.test(event.getPlayer()) || !service.blocksKitConsumables(event.getPlayer().getUniqueId())) return;
        event.setCancelled(true);
        tell(event.getPlayer(), "Save your kit for the fight.");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity().getShooter() instanceof Player player) || exempt.test(player)) return;
        if (!service.blocksProjectiles(player.getUniqueId())) return;
        event.setCancelled(true);
        tell(player, "Wait for the fight to start.");
    }

    /** The countdown freeze: the duelist stays on their spawn block; head rotation is kept. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null) return;
        Location from = event.getFrom();
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()) {
            return;
        }
        Optional<Location> spot = service.frozenAt(event.getPlayer().getUniqueId());
        if (spot.isEmpty()) return;
        Location back = spot.get().clone();
        back.setYaw(to.getYaw());
        back.setPitch(to.getPitch());
        event.setTo(back);
    }
}

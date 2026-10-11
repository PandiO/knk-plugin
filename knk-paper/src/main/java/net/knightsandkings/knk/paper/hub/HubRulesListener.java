package net.knightsandkings.knk.paper.hub;

import java.util.Objects;
import java.util.function.Predicate;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.data.Openable;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.InventoryHolder;

import net.knightsandkings.knk.core.domain.settings.KnkHubRules;

/**
 * The hub's rules (hub DESIGN §4, §13), inside the selected hub's region only, so a same-world hub leaves the
 * gameplay around it alone. Damage rules hold for everyone; building and the interaction rules can be lifted with
 * {@code knk.hub.bypass} (staff building the hub). A player's own inventory is never restricted.
 */
public final class HubRulesListener implements Listener {

    private final HubService hub;
    private final Predicate<Player> bypass;

    public HubRulesListener(HubService hub, Predicate<Player> bypass) {
        this.hub = Objects.requireNonNull(hub, "hub");
        this.bypass = Objects.requireNonNull(bypass, "bypass");
    }

    private KnkHubRules rules() {
        return hub.rules();
    }

    private boolean restricted(Player player, Location where, boolean allowed) {
        return !allowed && hub.inHub(where) && !bypass.test(player);
    }

    // ---- damage ----

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!hub.configured()) {
            return;
        }
        Entity victim = event.getEntity();
        Player attacker = event instanceof EntityDamageByEntityEvent byEntity ? attackingPlayer(byEntity.getDamager()) : null;
        KnkHubRules rules = rules();

        if (victim instanceof Player player) {
            boolean falling = event.getCause() == EntityDamageEvent.DamageCause.VOID;
            // Below a void hub no region reaches: judge the column instead.
            boolean inHub = falling ? hub.inHubColumn(player.getLocation()) : hub.inHub(player.getLocation());
            if (attacker != null && !rules.damagePlayers() && (inHub || hub.inHub(attacker.getLocation()))) {
                event.setCancelled(true);
                return;
            }
            if (inHub && !rules.takeDamage()) {
                event.setCancelled(true);
                if (falling) {
                    // Falling out of a void hub: back to its spawn instead of falling forever.
                    hub.admissionSpawn().ifPresent(player::teleport);
                }
            }
            return;
        }
        if (attacker == null) {
            return;
        }
        if (victim instanceof Hanging || victim instanceof ArmorStand) {
            if (restricted(attacker, victim.getLocation(), rules.useItemFramesPaintings())) {
                event.setCancelled(true);
            }
            return;
        }
        if (!rules.damageEntities() && hub.inHub(victim.getLocation())) {
            event.setCancelled(true);
        }
    }

    private static Player attackingPlayer(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }

    // ---- building ----

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (restricted(event.getPlayer(), event.getBlock().getLocation(), rules().buildBreak())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (restricted(event.getPlayer(), event.getBlock().getLocation(), rules().buildBreak())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (restricted(event.getPlayer(), event.getBlock().getLocation(), rules().buildBreak())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (restricted(event.getPlayer(), event.getBlock().getLocation(), rules().buildBreak())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (event.getPlayer() != null && restricted(event.getPlayer(), event.getEntity().getLocation(), rules().buildBreak())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        Player remover = attackingPlayer(event.getRemover());
        if (remover != null && restricted(remover, event.getEntity().getLocation(), rules().useItemFramesPaintings())) {
            event.setCancelled(true);
        }
    }

    // ---- items ----

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (restricted(event.getPlayer(), event.getPlayer().getLocation(), rules().dropPickup())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && restricted(player, player.getLocation(), rules().dropPickup())) {
            event.setCancelled(true);
        }
    }

    // ---- interactions ----

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        // In the hub first: classifying reads the block's state, which is not free on every click on the server.
        if (block == null || !hub.configured() || !hub.inHub(block.getLocation()) || bypass.test(event.getPlayer())) {
            return;
        }
        KnkHubRules rules = rules();
        boolean refused = switch (classify(event.getAction(), block)) {
            case DOOR -> !rules.useDoors();
            case STORAGE -> !rules.useStorage();
            case TRAMPLE -> !rules.buildBreak();
            case OTHER -> false;
        };
        if (refused) {
            event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
            if (event.getAction() == Action.PHYSICAL) {
                event.setCancelled(true);
            }
        }
    }

    enum BlockUse { DOOR, STORAGE, TRAMPLE, OTHER }

    static BlockUse classify(Action action, Block block) {
        if (action == Action.PHYSICAL) {
            return block.getType() == Material.FARMLAND ? BlockUse.TRAMPLE : BlockUse.OTHER;
        }
        if (action != Action.RIGHT_CLICK_BLOCK) {
            return BlockUse.OTHER;
        }
        // Storage first: a barrel's block data is Openable too. Chests, barrels, shulker boxes, hoppers, furnaces, ...
        // - not the ender chest: that is the player's own.
        if (block.getState() instanceof Container) {
            return BlockUse.STORAGE;
        }
        return block.getBlockData() instanceof Openable ? BlockUse.DOOR : BlockUse.OTHER;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Entity target = event.getRightClicked();
        KnkHubRules rules = rules();
        boolean allowed;
        if (target instanceof Hanging) {
            allowed = rules.useItemFramesPaintings();
        } else if (target instanceof AbstractVillager || target.hasMetadata("NPC")) {
            allowed = rules.useNpcs();
        } else if (target instanceof InventoryHolder && !(target instanceof Player)) {
            allowed = rules.useStorage(); // chest minecarts and boats, donkeys' packs
        } else {
            return;
        }
        if (restricted(event.getPlayer(), target.getLocation(), allowed)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (restricted(event.getPlayer(), event.getRightClicked().getLocation(), rules().useItemFramesPaintings())) {
            event.setCancelled(true);
        }
    }
}

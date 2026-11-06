package net.knightsandkings.knk.paper.siege.survival;

import net.knightsandkings.knk.paper.events.UserDataLoadedEvent;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The ghost rules of DESIGN §3b as event cancellations, for every survival match at once: a ghost takes no damage,
 * loses no hunger, breaks/places nothing, interacts with nothing, attacks nobody, picks up and drops nothing, opens no
 * container, uses no item; NPCs can't target a ghost ({@code EntityTargetLivingEntityEvent} safety net) and
 * projectiles pass through; a ghost leaving the scenario area is sent back to a team spawn (S60); after a join hold
 * released invulnerability ({@code JoinLoadingGuard}), the ghost effects are re-applied. Chat is untouched.
 */
public final class SurvivalGhostListener implements Listener {

    /** What the listener asks the runtime. */
    public interface Ghosts {
        boolean isGhost(UUID playerId);

        /** The match's area and ghost registry for a ghost (empty when the player is no ghost). */
        Optional<GhostContext> contextOf(UUID playerId);
    }

    public record GhostContext(SurvivalGhosts ghosts, ScenarioArea area, Function<Player, Optional<Location>> spawn) {
    }

    private final Ghosts ghosts;

    public SurvivalGhostListener(Ghosts ghosts) {
        this.ghosts = ghosts;
    }

    private boolean ghost(Entity entity) {
        return entity instanceof Player player && ghosts.isGhost(player.getUniqueId());
    }

    private static Entity shooterOf(Entity entity) {
        if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) return shooter;
        return entity;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (ghost(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (ghost(event.getEntity()) || ghost(shooterOf(event.getDamager()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHunger(FoodLevelChangeEvent event) {
        if (ghost(event.getEntity()) && event.getFoodLevel() < ((Player) event.getEntity()).getFoodLevel()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (ghosts.isGhost(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (ghosts.isGhost(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        if (ghosts.isGhost(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent event) {
        if (ghosts.isGhost(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent event) {
        if (ghosts.isGhost(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (ghosts.isGhost(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPickup(EntityPickupItemEvent event) {
        if (ghost(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (ghosts.isGhost(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onOpenInventory(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player) || !ghosts.isGhost(player.getUniqueId())) return;
        InventoryType type = event.getInventory().getType();
        if (type != InventoryType.PLAYER && type != InventoryType.CRAFTING && type != InventoryType.CREATIVE) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (ghost(event.getTarget())) {
            event.setTarget(null);
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (event.getHitEntity() != null && ghost(event.getHitEntity())) event.setCancelled(true);
    }

    /** S60: a ghost outside the scenario area returns to a team spawn. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null || !event.hasChangedBlock()) return;
        Player player = event.getPlayer();
        if (!ghosts.isGhost(player.getUniqueId())) return;
        ghosts.contextOf(player.getUniqueId()).ifPresent(ctx -> {
            if (ctx.area().contains(to)) return;
            ctx.spawn().apply(player).ifPresent(spawn -> {
                event.setCancelled(true);
                player.teleport(spawn);
                player.sendActionBar(net.kyori.adventure.text.Component.text("Ghosts stay inside the scenario area.",
                    net.knightsandkings.knk.paper.siege.SiegeMessages.INFO));
            });
        });
    }

    /** The join hold released invulnerability: a ghost gets it back (checks report §2). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onUserDataLoaded(UserDataLoadedEvent event) {
        Player player = event.getPlayer();
        if (player == null || !ghosts.isGhost(player.getUniqueId())) return;
        ghosts.contextOf(player.getUniqueId()).ifPresent(ctx -> ctx.ghosts().applyEffects(player));
    }
}

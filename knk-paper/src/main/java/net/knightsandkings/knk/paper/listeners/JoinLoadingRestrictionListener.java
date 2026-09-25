package net.knightsandkings.knk.paper.listeners;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import net.knightsandkings.knk.paper.user.JoinLoadingGuard;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Cancels everything a player held by {@link JoinLoadingGuard} could otherwise still do in
 * {@code GameMode.ADVENTURE} - Adventure mode alone only stops block breaking/placing, not
 * combat, item use, entity interaction, dropping items, gliding, or opening inventories, all
 * of which could touch coins/gems/XP or reveal restricted content before the player's real
 * data/permissions have loaded. Registered at LOWEST/cancel so nothing downstream (gates,
 * enchantments, menus) ever sees the event for a still-loading player.
 * <p>
 * {@code ignoreCancelled = true} everywhere here doubles as "only speak up if this would
 * otherwise have worked" - for {@link EntityDamageByEntityEvent} in particular, Bukkit starts
 * the event pre-cancelled when the world's PVP flag is off, so a handler at LOWEST with
 * ignoreCancelled never runs (and never messages anyone) for a hit that was never going to
 * land anyway.
 * <p>
 * Whoever's action got blocked is told why, with a short per-player cooldown so repeatedly
 * clicking/hitting doesn't spam chat: the loading player learns their own attempt didn't work
 * (in addition to the persistent action-bar reminder from {@link JoinLoadingGuard}), and
 * anyone whose interaction with a still-loading player got cancelled is told that instead.
 */
public class JoinLoadingRestrictionListener implements Listener {
    private static final long NOTICE_COOLDOWN_MILLIS = 2000L;
    private static final String SELF_LOADING_MESSAGE = "You can't do that yet - your account is still loading.";
    private static final String TARGET_LOADING_MESSAGE = "This player's data is still being fetched.";

    private final JoinLoadingGuard joinLoadingGuard;
    private final Map<UUID, Long> lastNoticeAt = new ConcurrentHashMap<>();

    public JoinLoadingRestrictionListener(JoinLoadingGuard joinLoadingGuard) {
        this.joinLoadingGuard = joinLoadingGuard;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (joinLoadingGuard.isLoading(player.getUniqueId())) {
            event.setCancelled(true);
            notify(player, SELF_LOADING_MESSAGE, NamedTextColor.RED);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        if (joinLoadingGuard.isLoading(player.getUniqueId())) {
            event.setCancelled(true);
            notify(player, SELF_LOADING_MESSAGE, NamedTextColor.RED);
            return;
        }

        if (event.getRightClicked() instanceof Player target && joinLoadingGuard.isLoading(target.getUniqueId())) {
            event.setCancelled(true);
            notify(player, TARGET_LOADING_MESSAGE, NamedTextColor.YELLOW);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Player attacker = resolvePlayerDamager(event.getDamager());
        if (attacker == null) {
            return;
        }

        if (joinLoadingGuard.isLoading(attacker.getUniqueId())) {
            event.setCancelled(true);
            notify(attacker, SELF_LOADING_MESSAGE, NamedTextColor.RED);
            return;
        }

        if (event.getEntity() instanceof Player victim && joinLoadingGuard.isLoading(victim.getUniqueId())) {
            event.setCancelled(true);
            notify(attacker, TARGET_LOADING_MESSAGE, NamedTextColor.YELLOW);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (joinLoadingGuard.isLoading(player.getUniqueId())) {
            event.setCancelled(true);
            notify(player, SELF_LOADING_MESSAGE, NamedTextColor.RED);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onToggleGlide(EntityToggleGlideEvent event) {
        if (!event.isGliding()) {
            return; // only block starting to glide, not the elytra turning itself off
        }
        if (event.getEntity() instanceof Player player && joinLoadingGuard.isLoading(player.getUniqueId())) {
            event.setCancelled(true);
            notify(player, SELF_LOADING_MESSAGE, NamedTextColor.RED);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        HumanEntity who = event.getPlayer();
        if (who instanceof Player player && joinLoadingGuard.isLoading(player.getUniqueId())) {
            event.setCancelled(true);
            notify(player, SELF_LOADING_MESSAGE, NamedTextColor.RED);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (joinLoadingGuard.isLoading(player.getUniqueId())) {
            event.setCancelled(true);
            notify(player, SELF_LOADING_MESSAGE, NamedTextColor.RED);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (joinLoadingGuard.isLoading(player.getUniqueId())) {
            event.setCancelled(true);
            notify(player, SELF_LOADING_MESSAGE, NamedTextColor.RED);
        }
    }

    /**
     * Resolves the attacking player for a melee hit, or the player that shot a projectile.
     */
    private Player resolvePlayerDamager(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }

    /**
     * Sends an action-bar notice, rate-limited per recipient so mashing a blocked action
     * doesn't spam them.
     */
    private void notify(Player recipient, String message, NamedTextColor color) {
        UUID uuid = recipient.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastNoticeAt.get(uuid);
        if (last != null && now - last < NOTICE_COOLDOWN_MILLIS) {
            return;
        }
        lastNoticeAt.put(uuid, now);
        recipient.sendActionBar(Component.text(message).color(color));
    }
}

package net.knightsandkings.knk.paper.listeners;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import net.knightsandkings.knk.core.teleport.WarmupBook;
import net.knightsandkings.knk.core.teleport.WarmupCancelReason;
import net.knightsandkings.knk.paper.teleport.TeleportRequestService;
import net.knightsandkings.knk.paper.teleport.TeleportService;

/**
 * Cancels a running teleport warmup (docs/specs/teleport/DESIGN.md §3.4 step 2): when the player
 * moves to another block (looking around doesn't count - v1 cancelled on any move), takes any
 * damage (v1's damage cancel was never live), is teleported by anything else, dies or quits.
 * Being frozen is caught by {@link TeleportService#tick}. MONITOR + ignoreCancelled: only moves and
 * damage that really happened count.
 * <p>
 * Also drops a player's pending teleport requests (sent and received) when they quit or die
 * (DESIGN §3.5, Phase 3).
 */
public class TeleportWarmupListener implements Listener {

    private static final Logger LOGGER = Logger.getLogger(TeleportWarmupListener.class.getName());

    private final TeleportService teleportService;
    /** Other per-player teleport state to drop when a player quits (e.g. {@code WarpCommand::forget}). */
    private final List<Consumer<UUID>> quitHooks = new CopyOnWriteArrayList<>();
    /** Null when teleport requests aren't wired (tests of the engine alone). */
    private final TeleportRequestService requestService;

    public TeleportWarmupListener(TeleportService teleportService) {
        this(teleportService, null);
    }

    public TeleportWarmupListener(TeleportService teleportService, TeleportRequestService requestService) {
        this.teleportService = Objects.requireNonNull(teleportService, "teleportService must not be null");
        this.requestService = requestService;
    }

    /** Also run {@code hook} with the player's id when they quit, so per-player caches don't outlive them. */
    public void addQuitHook(Consumer<UUID> hook) {
        quitHooks.add(Objects.requireNonNull(hook, "hook must not be null"));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!teleportService.isWarmingUp(player.getUniqueId())) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to != null && WarmupBook.changesBlock(from.getBlockX(), from.getBlockY(), from.getBlockZ(),
                to.getBlockX(), to.getBlockY(), to.getBlockZ())) {
            teleportService.cancelWarmup(player.getUniqueId(), WarmupCancelReason.MOVED);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        teleportService.cancelWarmup(event.getPlayer().getUniqueId(), WarmupCancelReason.TELEPORTED);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            teleportService.cancelWarmup(player.getUniqueId(), WarmupCancelReason.DAMAGED);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        teleportService.cancelWarmup(event.getEntity().getUniqueId(), WarmupCancelReason.DIED);
        if (requestService != null) {
            requestService.forget(event.getEntity());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (requestService != null) {
            requestService.forget(event.getPlayer());
        }
        UUID id = event.getPlayer().getUniqueId();
        teleportService.forget(id);
        for (Consumer<UUID> hook : quitHooks) {
            try {
                hook.accept(id);
            } catch (RuntimeException ex) {
                LOGGER.log(Level.WARNING, "[KnK Teleport] Quit cleanup failed for " + event.getPlayer().getName(), ex);
            }
        }
    }
}

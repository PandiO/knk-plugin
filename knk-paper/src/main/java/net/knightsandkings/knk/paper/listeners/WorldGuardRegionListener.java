package net.knightsandkings.knk.paper.listeners;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.util.Vector;

import net.knightsandkings.knk.core.regions.RegionTransitionDecision;
import net.knightsandkings.knk.core.regions.RegionTransitionType;
import net.knightsandkings.knk.paper.regions.WorldGuardRegionTracker;
import net.knightsandkings.knk.paper.utils.ColorOptions;
import net.kyori.adventure.text.Component;

import java.util.List;
import java.util.logging.Logger;

public class WorldGuardRegionListener implements Listener {
    private static final Logger LOGGER = Logger.getLogger(WorldGuardRegionListener.class.getName());
    
    private final WorldGuardRegionTracker tracker;

    public WorldGuardRegionListener(WorldGuardRegionTracker tracker) {
        this.tracker = tracker;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        LOGGER.fine("[KnK Listener] PlayerMoveEvent: " + event.getPlayer().getName());
        if (event.getPlayer().isInsideVehicle()) {
            return;  // a rider's moves are judged by onVehicleMove
        }
        handle(event.getPlayer(), event.getFrom(), event.getTo(), event);
    }

    /**
     * A rider (horse, boat, minecart, ...) moves without a PlayerMoveEvent, so AllowEntry/AllowExit
     * are judged here (KNG-55). A vehicle move can't be cancelled: a refused rider is taken off, and
     * the rider and the then-empty vehicle are put back where the vehicle came from.
     */
    @EventHandler
    public void onVehicleMove(VehicleMoveEvent event) {
        Vehicle vehicle = event.getVehicle();
        for (Entity passenger : List.copyOf(vehicle.getPassengers())) {
            if (!(passenger instanceof Player player)) {
                continue;
            }
            RegionTransitionDecision decision = tracker.handleMove(player, event.getFrom(), event.getTo());
            if (decision == null) {
                continue;
            }
            if (!decision.isMovementAllowed() && !tracker.bypassesDenials(player)) {
                LOGGER.info("[KnK Listener] " + player.getName() + " ride CANCELLED: " + decision.getMessage().orElse("(none)"));
                vehicle.removePassenger(player);
                if (vehicle.getPassengers().isEmpty()) {
                    vehicle.setVelocity(new Vector());
                    vehicle.teleport(event.getFrom());
                }
                tracker.enforcementTeleport(player, event.getFrom());
                decision.getMessage().ifPresent(msg -> player.sendActionBar(Component.text(msg).color(ColorOptions.error)));
            } else if (decision.isMovementAllowed() && decision.getType() == RegionTransitionType.ENTER) {
                decision.getMessage().ifPresent(msg -> player.sendActionBar(Component.text(msg).color(ColorOptions.message)));
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        LOGGER.info("[KnK Listener] PlayerTeleportEvent: " + event.getPlayer().getName());
        handle(event.getPlayer(), event.getFrom(), event.getTo(), event);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        LOGGER.info("[KnK Listener] PlayerJoinEvent: " + event.getPlayer().getName());
        Player player = event.getPlayer();
        RegionTransitionDecision decision = tracker.handleJoin(player);
        
        if (decision != null) {
            LOGGER.info("[KnK Listener] " + player.getName() + " join decision: allowed=" + decision.isMovementAllowed() + 
                        ", message=" + decision.getMessage().orElse("(none)"));
            
            if (!decision.isMovementAllowed()) {
                // Entry denied: send error message and teleport to world spawn
                LOGGER.warning("[KnK Listener] " + player.getName() + " denied entry to join location");
                decision.getMessage().ifPresent(msg -> 
                    player.sendMessage(Component.text(msg).color(ColorOptions.error))
                );
                // Teleport to world spawn as safe fallback (not judged itself: the join spot may forbid leaving)
                Location spawnLocation = player.getWorld().getSpawnLocation();
                tracker.enforcementTeleport(player, spawnLocation);
            } else if (decision.getType() == RegionTransitionType.ENTER) {
                // Entry allowed: send welcome message
                decision.getMessage().ifPresent(msg -> 
                    player.sendMessage(Component.text(msg).color(ColorOptions.message))
                );
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        LOGGER.info("[KnK Listener] PlayerQuitEvent: " + event.getPlayer().getName());
        tracker.handleQuit(event.getPlayer());
    }

    private void handle(Player player, Location from, Location to, org.bukkit.event.Cancellable event) {
        RegionTransitionDecision decision = tracker.handleMove(player, from, to);
        if (decision == null) {
            LOGGER.fine("[KnK Listener] " + player.getName() + " decision=null (no change or suppressed)");
            return;
        }
        
        LOGGER.info("[KnK Listener] " + player.getName() + " decision: allowed=" + decision.isMovementAllowed() + 
                    ", message=" + decision.getMessage().orElse("(none)"));
        
        if (!decision.isMovementAllowed() && tracker.bypassesDenials(player)) {
            // knk.region.bypass (docs/specs/teleport/DESIGN.md §4 D11): staff walk and teleport
            // through closed domains; a staff teleport of another player carries the staff
            // member's bypass (TeleportService.hasInFlightBypass, wired in KnKPlugin).
            LOGGER.info("[KnK Listener] " + player.getName() + " movement denial BYPASSED (knk.region.bypass)");
            return;
        }
        if (!decision.isMovementAllowed()) {
            // Movement denied: send deny message in RED and cancel the event. The tracker kept the
            // player's regions as they were (KNG-55), so the next step over the border is denied too.
            LOGGER.info("[KnK Listener] " + player.getName() + " movement CANCELLED");
            event.setCancelled(true);
            decision.getMessage().ifPresent(msg -> player.sendActionBar(Component.text(msg).color(ColorOptions.error)));
            return;
        }
        if (decision.getType() != null && decision.getType() == RegionTransitionType.ENTER) {
            decision.getMessage().ifPresent(msg -> player.sendActionBar(Component.text(msg).color(ColorOptions.message)));
        }
        // Movement allowed: send welcome message in YELLOW
    }
}

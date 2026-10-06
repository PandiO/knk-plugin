package net.knightsandkings.knk.paper.listeners;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;

import net.knightsandkings.knk.core.regions.RegionTransitionDecision;
import net.knightsandkings.knk.core.regions.RegionTransitionType;
import net.knightsandkings.knk.paper.regions.WorldGuardRegionTracker;
import net.knightsandkings.knk.paper.utils.ColorOptions;
import net.kyori.adventure.text.Component;

import java.util.List;
import java.util.logging.Logger;

/**
 * Feeds the moves that happened to {@link WorldGuardRegionTracker} for welcome messages, gates and
 * region events. It enforces nothing: domain AllowEntry/AllowExit is WorldGuard's job since KNG-56
 * ({@code regions.access.DomainAccessHandler}, {@code DomainAccessListener}), so this runs at
 * MONITOR, after WorldGuard has put a refused player back.
 */
public class WorldGuardRegionListener implements Listener {
    private static final Logger LOGGER = Logger.getLogger(WorldGuardRegionListener.class.getName());

    private final WorldGuardRegionTracker tracker;

    public WorldGuardRegionListener(WorldGuardRegionTracker tracker) {
        this.tracker = tracker;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getPlayer().isInsideVehicle()) {
            return;  // a rider's moves are described by onVehicleMove
        }
        handle(event.getPlayer(), event.getFrom(), event.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        handle(event.getPlayer(), event.getFrom(), event.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVehicleMove(VehicleMoveEvent event) {
        for (Entity passenger : List.copyOf(event.getVehicle().getPassengers())) {
            if (passenger instanceof Player player) {
                handle(player, event.getFrom(), event.getTo());
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        RegionTransitionDecision decision = tracker.handleJoin(player);
        if (decision != null && decision.isMovementAllowed() && decision.getType() == RegionTransitionType.ENTER) {
            decision.getMessage().ifPresent(msg -> player.sendMessage(Component.text(msg).color(ColorOptions.message)));
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        tracker.handleQuit(event.getPlayer());
    }

    private void handle(Player player, Location from, Location to) {
        RegionTransitionDecision decision = tracker.handleMove(player, from, to);
        if (decision == null) {
            return;
        }
        LOGGER.fine("[KnK Listener] " + player.getName() + " transition: " + decision.getMessage().orElse("(none)"));
        if (decision.isMovementAllowed() && decision.getType() == RegionTransitionType.ENTER) {
            decision.getMessage().ifPresent(msg -> player.sendActionBar(Component.text(msg).color(ColorOptions.message)));
        }
    }
}

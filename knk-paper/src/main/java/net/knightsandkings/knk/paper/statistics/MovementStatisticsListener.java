package net.knightsandkings.knk.paper.statistics;

import org.bukkit.Location;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;

import net.knightsandkings.knk.core.statistics.MovementClassifier;

/**
 * Distance per mode (DESIGN.md §F.9): every {@link PlayerMoveEvent} is one segment - foot (swimming
 * also counts as swim), flying (elytra or flight), vehicle when riding a mount - and every boat or
 * minecart {@link VehicleMoveEvent} one vehicle segment for each player riding it. Not counted:
 * teleports (their own event), world changes,
 * segments longer than {@code statistics.movement.max-segment-blocks}, AFK players and the excluded
 * game modes. Cheap early-outs first (look-only moves, no session); no allocation per event.
 */
public final class MovementStatisticsListener implements Listener {

    private final StatisticsService service;
    private final double maxSegmentBlocks;

    public MovementStatisticsListener(StatisticsService service, double maxSegmentBlocks) {
        this.service = service;
        this.maxSegmentBlocks = maxSegmentBlocks;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        if (dx == 0 && dy == 0 && dz == 0) {
            return;
        }
        Player player = event.getPlayer();
        Entity vehicle = player.getVehicle();
        if (firesVehicleMoveEvents(vehicle) || !eligible(player) || from.getWorld() != to.getWorld()) {
            return; // boat or minecart: counted from the vehicle's own move event
        }
        // A mount (horse, pig, strider, camel ...) fires no VehicleMoveEvent: Paper only fires the
        // rider's PlayerMoveEvent for it (smoke test finding 4).
        MovementClassifier.Mode mode = MovementClassifier.classify(vehicle != null, player.isGliding(), player.isFlying(),
                player.isInWater(), MovementClassifier.length(dx, dy, dz), maxSegmentBlocks);
        if (mode != null) {
            service.addDistance(player.getUniqueId(), mode, MovementClassifier.length(dx, dy, dz));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleMove(VehicleMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (!firesVehicleMoveEvents(event.getVehicle()) || from.getWorld() != to.getWorld()) {
            return; // a mount is counted from its rider's PlayerMoveEvent, never twice
        }
        double length = MovementClassifier.length(to.getX() - from.getX(), to.getY() - from.getY(), to.getZ() - from.getZ());
        if (!(length > 0)) {
            return;
        }
        for (Entity passenger : event.getVehicle().getPassengers()) {
            if (passenger instanceof Player player && eligible(player)) {
                MovementClassifier.Mode mode = MovementClassifier.classify(true, false, false, false, length, maxSegmentBlocks);
                if (mode != null) {
                    service.addDistance(player.getUniqueId(), mode, length);
                }
            }
        }
    }

    /**
     * Boats and minecarts fire {@link VehicleMoveEvent} from their own tick (Paper 1.21.10); a
     * player-steered mount doesn't, only its rider's {@link PlayerMoveEvent}.
     */
    static boolean firesVehicleMoveEvents(Entity vehicle) {
        return vehicle instanceof Boat || vehicle instanceof Minecart;
    }

    private boolean eligible(Player player) {
        return service.hasSession(player.getUniqueId()) && !service.isAfk(player.getUniqueId()) && !service.excluded(player);
    }
}

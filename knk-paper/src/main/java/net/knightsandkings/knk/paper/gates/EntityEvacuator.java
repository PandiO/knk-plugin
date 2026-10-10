package net.knightsandkings.knk.paper.gates;

import io.papermc.paper.entity.TeleportFlag;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.core.gates.safety.GateSafeSpotFinder;
import net.knightsandkings.knk.core.gates.safety.GateSafeSpotFinder.CellFilter;
import net.knightsandkings.knk.core.gates.safety.GateSafeSpotFinder.Query;
import net.knightsandkings.knk.core.gates.target.GateBox;
import net.knightsandkings.knk.core.teleport.SafeLocationFinder.Spot;
import net.knightsandkings.knk.paper.teleport.BukkitBlockProbe;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Moves an entity out of a gate door's way (KNG-106): before a moving door's next blocks land on it
 * ({@code GateAnimationTask}), or when it is found inside a door's blocks ({@link GateSuffocationGuard}).
 * <p>
 * The destination is {@link GateSafeSpotFinder}'s: standable (two passable blocks, solid ground, no
 * lava/fire/cactus, no drop), within {@link #SEARCH_RADIUS} blocks, on the side of the door the
 * entity is already on when that side has room, else the other side - and never in a cell the
 * caller blocks (the rest of the door's sweep, its region), in a cell any gate block occupies now,
 * or inside another nearby door's region. The entity is moved with its passengers (a player on a
 * horse moves with the horse).
 */
public final class EntityEvacuator {
    private static final Logger LOGGER = Logger.getLogger(EntityEvacuator.class.getName());
    /** Wide enough to get past a lowering drawbridge's landing area. */
    static final int SEARCH_RADIUS = 6;
    static final int VERTICAL_RANGE = 2;

    private EntityEvacuator() {
    }

    /**
     * @param avoid cells the destination must stay out of besides gate blocks and other doors (the
     *              door's remaining sweep, its region)
     * @return True if the entity was teleported to a safe spot
     */
    public static boolean evacuate(Entity entity, CachedGateDoor gate, GateManager gateManager, CellFilter avoid) {
        if (entity == null || gate == null || entity.isDead()) {
            return false;
        }
        Entity root = rootVehicle(entity);
        World world = root.getWorld();
        Location origin = root.getLocation();
        if (world == null || origin == null) {
            return false;
        }

        CellFilter blocked = (avoid == null ? CellFilter.NONE : avoid)
            .or(GateSafeSpots.gateBlocks(world, gateManager))
            .or(GateSafeSpots.otherDoors(world, gate, gateManager));
        Optional<Spot> spot = GateSafeSpotFinder.find(new BukkitBlockProbe(world), query(root, gate, gateManager), blocked);
        if (spot.isEmpty()) {
            LOGGER.fine("No safe evacuation spot for " + root.getType() + " near gate '" + gate.getName() + "'");
            return false;
        }

        Location destination = GateSafeSpots.toLocation(world, spot.get());
        destination.setYaw(origin.getYaw());
        destination.setPitch(origin.getPitch());
        root.teleport(destination, TeleportFlag.EntityState.RETAIN_PASSENGERS);
        root.setVelocity(new Vector(0, 0, 0));
        root.setFallDistance(0);
        LOGGER.fine("Evacuated " + root.getType() + " out of gate '" + gate.getName() + "' to " + destination);
        return true;
    }

    /**
     * The search: near the entity's box and feet, on the entity's side of the door (the door's
     * closed footprint decides front and back).
     */
    static Query query(Entity entity, CachedGateDoor gate, GateManager gateManager) {
        BoundingBox box = entity.getBoundingBox();
        Location location = entity.getLocation();
        GateBox near = new GateBox(box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ());
        GateBox door = closedBox(gate, gateManager);
        Vector axis = resolveFaceAxis(gate);
        double axisX = axis == null ? 0 : axis.getX();
        double axisZ = axis == null ? 0 : axis.getZ();
        int side = door == null ? 0 : GateSafeSpotFinder.sideOf(door, axisX, axisZ, location.getX(), location.getZ());
        return new Query(near, door, location.getY(), axisX, axisZ, side, SEARCH_RADIUS, VERTICAL_RANGE);
    }

    /** The box of the door's closed footprint; its region ({@link GateDoorBounds}) without one. */
    static GateBox closedBox(CachedGateDoor gate, GateManager gateManager) {
        GateBox box = null;
        List<Vector> closed = gateManager.closedFootprint(gate.getId());
        for (Vector block : closed) {
            box = GateBox.ofBlock(block.getBlockX(), block.getBlockY(), block.getBlockZ()).union(box);
        }
        return box != null ? box : GateDoorBounds.of(gate, gateManager);
    }

    static Entity rootVehicle(Entity entity) {
        Entity root = entity;
        for (int depth = 0; depth < 16 && root.getVehicle() != null; depth++) {
            root = root.getVehicle();
        }
        return root;
    }

    /** Package-private (not private) so it's directly unit-testable without a live Bukkit World,
     *  matching GatePassThroughService's/GateDisplayManager's pure-geometry-helper convention. */
    static Vector resolveFaceAxis(CachedGateDoor gate) {
        Vector axis = EntityPusher.vectorFromFaceDirection(gate.getFaceDirection());
        if (axis == null || axis.lengthSquared() == 0) {
            axis = gate.getNAxis();
        }

        if (axis == null) {
            return null;
        }

        Vector horizontal = new Vector(axis.getX(), 0, axis.getZ());
        return horizontal.lengthSquared() == 0 ? null : horizontal.normalize();
    }
}

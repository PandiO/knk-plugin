package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;

import java.util.logging.Logger;

/**
 * Teleports entities out of a gate's animation volume so moving blocks never suffocate them.
 * FaceDirection defines the inside/outside axis; the entity is moved to whichever side it is
 * already closest to, falling back to the opposite side when no safe spot is available.
 */
public final class EntityEvacuator {
    private static final Logger LOGGER = Logger.getLogger(EntityEvacuator.class.getName());
    private static final int MAX_SEARCH_DISTANCE = 4;
    private static final int[] VERTICAL_OFFSETS = {0, 1, -1, 2, -2};

    private EntityEvacuator() {
    }

    /**
     * @return True if the entity was teleported to a safe spot outside the gate plane
     */
    public static boolean evacuate(Entity entity, CachedGateDoor gate) {
        if (entity == null || gate == null || entity.isDead()) {
            return false;
        }

        Vector faceAxis = resolveFaceAxis(gate);
        if (faceAxis == null) {
            return false;
        }

        Location origin = entity.getLocation();
        Vector anchor = gate.getAnchorPoint();
        double side = anchor != null ? origin.toVector().subtract(anchor).dot(faceAxis) : 0.0;

        Vector primary = side < 0 ? faceAxis.clone().multiply(-1) : faceAxis.clone();
        Location destination = findSafeSpot(origin, primary);
        if (destination == null) {
            destination = findSafeSpot(origin, primary.clone().multiply(-1));
        }

        if (destination == null) {
            LOGGER.fine("No safe evacuation spot for " + entity.getType() + " near gate '" + gate.getName() + "'");
            return false;
        }

        destination.setYaw(origin.getYaw());
        destination.setPitch(origin.getPitch());
        entity.teleport(destination);
        entity.setVelocity(new Vector(0, 0, 0));
        LOGGER.fine("Evacuated " + entity.getType() + " out of gate '" + gate.getName() + "' to " + destination);
        return true;
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

    private static Location findSafeSpot(Location origin, Vector direction) {
        World world = origin.getWorld();
        if (world == null) {
            return null;
        }

        for (int distance = 1; distance <= MAX_SEARCH_DISTANCE; distance++) {
            for (int verticalOffset : VERTICAL_OFFSETS) {
                Location candidate = origin.clone().add(
                    direction.getX() * distance,
                    verticalOffset,
                    direction.getZ() * distance
                );

                if (isSafeStandingSpot(world, candidate)) {
                    return new Location(
                        world,
                        candidate.getBlockX() + 0.5,
                        candidate.getBlockY(),
                        candidate.getBlockZ() + 0.5
                    );
                }
            }
        }

        return null;
    }

    private static boolean isSafeStandingSpot(World world, Location location) {
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();

        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return false;
        }

        if (y - 1 < world.getMinHeight() || y + 1 >= world.getMaxHeight()) {
            return false;
        }

        Block feet = world.getBlockAt(x, y, z);
        Block head = world.getBlockAt(x, y + 1, z);
        Block ground = world.getBlockAt(x, y - 1, z);

        return feet.isPassable() && !feet.isLiquid()
            && head.isPassable() && !head.isLiquid()
            && !ground.isPassable();
    }
}

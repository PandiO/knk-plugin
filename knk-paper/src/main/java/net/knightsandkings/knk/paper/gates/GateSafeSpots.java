package net.knightsandkings.knk.paper.gates;

import java.util.Optional;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;

import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.core.gates.GateSpatialIndex;
import net.knightsandkings.knk.core.gates.safety.GateSafeSpotFinder;
import net.knightsandkings.knk.core.gates.safety.GateSafeSpotFinder.CellFilter;
import net.knightsandkings.knk.core.gates.safety.GateSafeSpotFinder.Query;
import net.knightsandkings.knk.core.gates.target.GateBox;
import net.knightsandkings.knk.core.teleport.SafeLocationFinder.Spot;
import net.knightsandkings.knk.paper.teleport.BukkitBlockProbe;

/**
 * Where a player can stand next to a gate door (KNG-105, KNG-106): {@link GateSafeSpotFinder} over
 * the Bukkit world. Never inside a door's region ({@link GateDoorBounds}: closed footprint plus
 * captured regions, so an open door's opening counts too), never in a cell a gate block occupies
 * right now ({@link GateSpatialIndex}), and never inside another nearby door's region.
 * Main thread only (it reads blocks).
 */
public final class GateSafeSpots {

    /** How far (blocks, horizontally) from a door's region {@code /gatedoor tp} looks. */
    public static final int TELEPORT_RADIUS = 4;
    /** How far above/below the door's bottom it looks. */
    static final int TELEPORT_VERTICAL_RANGE = 3;
    /** Other doors whose anchor is this close count as obstacles. */
    private static final double OTHER_DOOR_RANGE = 48;

    private GateSafeSpots() {
    }

    /**
     * The nearest standable spot next to the door, preferring its front and back faces, facing the
     * door; empty when nothing within {@link #TELEPORT_RADIUS} blocks is safe.
     */
    public static Optional<Location> nextToDoor(World world, CachedGateDoor door, GateManager gateManager) {
        GateBox box = GateDoorBounds.of(door, gateManager);
        if (world == null || box == null) {
            return Optional.empty();
        }
        Vector axis = faceAxis(door);
        Query query = new Query(box, null, box.minY(), axis == null ? 0 : axis.getX(), axis == null ? 0 : axis.getZ(), 0,
            TELEPORT_RADIUS, TELEPORT_VERTICAL_RANGE);
        CellFilter blocked = CellFilter.inside(box).or(gateBlocks(world, gateManager)).or(otherDoors(world, door, gateManager));
        return GateSafeSpotFinder.find(new BukkitBlockProbe(world), query, blocked)
            .map(spot -> facing(toLocation(world, spot), box));
    }

    /** Cells a gate block occupies right now (any door, any state), from the spatial index. */
    static CellFilter gateBlocks(World world, GateManager gateManager) {
        GateSpatialIndex index = gateManager.getSpatialIndex();
        String worldName = world.getName();
        return (x, y, z) -> index != null && index.lookup(worldName, x, y, z) != null;
    }

    /** The regions of the other doors near this one (same world). */
    static CellFilter otherDoors(World world, CachedGateDoor door, GateManager gateManager) {
        CellFilter filter = CellFilter.NONE;
        Vector anchor = door.getAnchorPoint();
        for (CachedGateDoor other : gateManager.getAllGates().values()) {
            if (other == null || other.getId() == door.getId() || !sameWorld(world, other)) {
                continue;
            }
            Vector otherAnchor = other.getAnchorPoint();
            if (anchor != null && otherAnchor != null && anchor.distance(otherAnchor) > OTHER_DOOR_RANGE) {
                continue;
            }
            GateBox box = GateDoorBounds.of(other, gateManager);
            if (box != null) {
                filter = filter.or(CellFilter.inside(box));
            }
        }
        return filter;
    }

    static boolean sameWorld(World world, CachedGateDoor door) {
        String name = door.getWorldName();
        return name == null || name.isBlank() || name.equals(world.getName());
    }

    /**
     * The door's horizontal face axis (its {@code FaceDirection}, else its n-axis), normalised;
     * null when it has none (then there is no front/back preference).
     */
    public static Vector faceAxis(CachedGateDoor door) {
        return EntityEvacuator.resolveFaceAxis(door);
    }

    static Location toLocation(World world, Spot spot) {
        return new Location(world, spot.x() + 0.5, spot.y(), spot.z() + 0.5);
    }

    /** The location turned to look at the box's middle (horizontally). */
    static Location facing(Location location, GateBox box) {
        double dx = (box.minX() + box.maxX()) / 2 - location.getX();
        double dz = (box.minZ() + box.maxZ()) / 2 - location.getZ();
        if (dx * dx + dz * dz > 1e-6) {
            location.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        }
        location.setPitch(0);
        return location;
    }
}

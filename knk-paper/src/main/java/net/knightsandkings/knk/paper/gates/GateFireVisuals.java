package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The picture of a burning gate door block (the continuous-damage "fire" mechanic, see
 * {@link GateFireSystem}): a regular fire block on the side of the door block the igniter stood on.
 * <p>
 * Purely visual and per viewer: the fire is sent to nearby players as a client-side block change and
 * never exists in the world, so it can't spread, burn the (possibly wooden) door blocks, hurt anyone
 * walking into it, or be left behind by a crash. The client renders its own flames, light and crackle for
 * it. Because a block change is forgotten when the chunk is resent, {@link #sync} re-sends every live
 * fire on each fire tick, which also shows it to players who came into range since; when a burn ends the
 * real block is sent back. Main thread only.
 */
public class GateFireVisuals {
    /** Players within this many blocks of a fire see it. */
    static final double VIEW_RADIUS = 64.0;

    private static final BlockFace[] SIDES = {
        BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN
    };

    /** One fire: the air cell it stands in and which way the door block it clings to lies from that cell. */
    record Cell(Vector at, BlockFace towardDoor) {
    }

    // gate id -> burning door block position -> its fire
    private final Map<Integer, Map<Vector, Cell>> cellsByGate = new HashMap<>();

    /**
     * Show fire on {@code door}, on the free side facing the attacker. A block that already shows a fire
     * keeps it (a re-ignite only refreshes the burn), so the fire doesn't jump around under a hail of arrows.
     *
     * @param source the igniter whose position decides the side; null when unknown
     * @param preferredSide the face the ignition struck, tried first; null when unknown
     */
    public void show(CachedGateDoor gate, Block door, Entity source, BlockFace preferredSide) {
        World world = door.getWorld();
        if (world == null) {
            return;
        }
        Vector doorPosition = new Vector(door.getX(), door.getY(), door.getZ());
        Map<Vector, Cell> cells = cellsByGate.computeIfAbsent(gate.getId(), id -> new HashMap<>());
        Cell cell = cells.get(doorPosition);
        if (cell == null) {
            BlockFace side = chooseSide(preferredSide, directionToSource(door, source), face -> isFree(door.getRelative(face)));
            if (side == null) {
                return; // walled in on every side: it still burns, there's just nowhere to draw the fire
            }
            Block at = door.getRelative(side);
            cell = new Cell(new Vector(at.getX(), at.getY(), at.getZ()), side.getOppositeFace());
            cells.put(doorPosition, cell);
        }
        send(world, cell);
    }

    /**
     * Bring the shown fires in line with the gate's burning blocks: re-send the ones still burning (new
     * viewers, resent chunks) and take down the ones that burned out.
     */
    public void sync(CachedGateDoor gate) {
        Map<Vector, Cell> cells = cellsByGate.get(gate.getId());
        if (cells == null) {
            return;
        }
        World world = Bukkit.getWorld(gate.getWorldName());
        List<Cell> gone = new ArrayList<>();
        Iterator<Map.Entry<Vector, Cell>> iterator = cells.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Vector, Cell> entry = iterator.next();
            if (gate.getBurningBlocks().containsKey(entry.getKey())) {
                send(world, entry.getValue());
            } else {
                iterator.remove();
                gone.add(entry.getValue());
            }
        }
        restoreUnlessStillShown(world, gone, cells);
        if (cells.isEmpty()) {
            cellsByGate.remove(gate.getId());
        }
    }

    /** Take down the fire of one door block (it was extinguished). */
    public void extinguish(CachedGateDoor gate, Vector doorPosition) {
        Map<Vector, Cell> cells = cellsByGate.get(gate.getId());
        if (cells == null) {
            return;
        }
        Cell cell = cells.remove(doorPosition);
        if (cell != null) {
            restoreUnlessStillShown(Bukkit.getWorld(gate.getWorldName()), List.of(cell), cells);
        }
        if (cells.isEmpty()) {
            cellsByGate.remove(gate.getId());
        }
    }

    /** Take down every fire of the gate (it stopped burning as a whole: destroyed, opened, ...). */
    public void clear(CachedGateDoor gate) {
        Map<Vector, Cell> cells = cellsByGate.remove(gate.getId());
        if (cells == null) {
            return;
        }
        restoreUnlessStillShown(Bukkit.getWorld(gate.getWorldName()), new ArrayList<>(cells.values()), Map.of());
    }

    /** The fires currently shown for a gate: burning door block position -> fire. */
    Map<Vector, Cell> cellsOf(CachedGateDoor gate) {
        Map<Vector, Cell> cells = cellsByGate.get(gate.getId());
        return cells == null ? Map.of() : cells;
    }

    /**
     * The side of a door block to draw its fire on: the struck face when it's free, otherwise the free
     * side that points most at the attacker, otherwise any free side. Null when no side is free.
     *
     * @param towardSource the direction from the door block to the igniter, or null when unknown
     */
    static BlockFace chooseSide(BlockFace preferred, Vector towardSource, Predicate<BlockFace> isFree) {
        if (preferred != null && isSide(preferred) && isFree.test(preferred)) {
            return preferred;
        }
        List<BlockFace> order = new ArrayList<>(List.of(SIDES));
        if (towardSource != null) {
            order.sort(Comparator.comparingDouble(
                face -> -(face.getModX() * towardSource.getX() + face.getModY() * towardSource.getY() + face.getModZ() * towardSource.getZ())));
        }
        for (BlockFace face : order) {
            if (isFree.test(face)) {
                return face;
            }
        }
        return null;
    }

    private static boolean isSide(BlockFace face) {
        for (BlockFace side : SIDES) {
            if (side == face) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFree(Block neighbor) {
        return neighbor != null && neighbor.isEmpty();
    }

    private static Vector directionToSource(Block door, Entity source) {
        if (source == null || source.getWorld() == null || !source.getWorld().equals(door.getWorld())) {
            return null;
        }
        Location at = source.getLocation();
        return new Vector(at.getX() - (door.getX() + 0.5), at.getY() - (door.getY() + 0.5), at.getZ() - (door.getZ() + 0.5));
    }

    private static void send(World world, Cell cell) {
        if (world == null) {
            return;
        }
        BlockData fire = fireData(cell.towardDoor());
        if (fire == null) {
            return;
        }
        Location location = locationOf(world, cell);
        for (Player viewer : world.getNearbyPlayers(location, VIEW_RADIUS)) {
            viewer.sendBlockChange(location, fire);
        }
    }

    /** Send the real block back to viewers, except for cells another still-burning block shows a fire in. */
    private static void restoreUnlessStillShown(World world, List<Cell> gone, Map<Vector, Cell> stillShown) {
        if (world == null) {
            return;
        }
        for (Cell cell : gone) {
            boolean shared = stillShown.values().stream().anyMatch(other -> other.at().equals(cell.at()));
            if (shared || !world.isChunkLoaded(cell.at().getBlockX() >> 4, cell.at().getBlockZ() >> 4)) {
                continue;
            }
            Location location = locationOf(world, cell);
            BlockData real = world.getBlockAt(location).getBlockData();
            for (Player viewer : world.getNearbyPlayers(location, VIEW_RADIUS)) {
                viewer.sendBlockChange(location, real);
            }
        }
    }

    private static Location locationOf(World world, Cell cell) {
        return new Location(world, cell.at().getBlockX(), cell.at().getBlockY(), cell.at().getBlockZ());
    }

    /** Fire clinging to the door block: a side flame when the fire block has a face that way, else floor fire. */
    private static BlockData fireData(BlockFace towardDoor) {
        BlockData data = Bukkit.createBlockData(Material.FIRE);
        if (data instanceof MultipleFacing fire && fire.getAllowedFaces().contains(towardDoor)) {
            fire.setFace(towardDoor, true);
        }
        return data;
    }
}

package net.knightsandkings.knk.core.gates;

import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * O(1) lookup of which gate (if any) currently occupies a world block position.
 * Maps world name -> packed block coordinate -> gate id, covering only the animated
 * door blocks of each gate (CachedGateDoor.getBlocks()), not the surrounding static structure.
 *
 * Every mutator here must be called on the main server thread, in lockstep with whatever
 * code path actually places/removes the corresponding world block, so the index never drifts
 * from reality. Never call these from the async persistence callbacks (API state sync) -
 * those only touch database state, not block positions.
 */
public class GateSpatialIndex {
    private final Map<String, Map<Long, Integer>> cellsByWorld = new HashMap<>();

    public static long packCell(int x, int y, int z) {
        return (((long) x & 0x3FFFFFFL) << 38)
            | (((long) y & 0xFFFL) << 26)
            | ((long) z & 0x3FFFFFFL);
    }

    public static long packCell(Vector position) {
        return packCell(position.getBlockX(), position.getBlockY(), position.getBlockZ());
    }

    public void put(String worldName, Vector position, int gateId) {
        if (worldName == null || position == null) {
            return;
        }
        cellsByWorld.computeIfAbsent(worldName, w -> new HashMap<>()).put(packCell(position), gateId);
    }

    public void remove(String worldName, Vector position) {
        if (worldName == null || position == null) {
            return;
        }
        Map<Long, Integer> cells = cellsByWorld.get(worldName);
        if (cells == null) {
            return;
        }
        cells.remove(packCell(position));
        if (cells.isEmpty()) {
            cellsByWorld.remove(worldName);
        }
    }

    /**
     * Move a gate's occupancy from one cell to another, skipping the removal when both
     * positions pack to the same cell (the block didn't actually move this frame).
     */
    public void move(String worldName, Vector oldPosition, Vector newPosition, int gateId) {
        if (oldPosition != null && (newPosition == null || packCell(oldPosition) != packCell(newPosition))) {
            remove(worldName, oldPosition);
        }
        if (newPosition != null) {
            put(worldName, newPosition, gateId);
        }
    }

    public Integer lookup(String worldName, int x, int y, int z) {
        if (worldName == null) {
            return null;
        }
        Map<Long, Integer> cells = cellsByWorld.get(worldName);
        return cells == null ? null : cells.get(packCell(x, y, z));
    }

    public Integer lookup(String worldName, Vector position) {
        return position == null ? null : lookup(worldName, position.getBlockX(), position.getBlockY(), position.getBlockZ());
    }

    public void putAll(String worldName, List<Vector> positions, int gateId) {
        if (positions == null) {
            return;
        }
        for (Vector position : positions) {
            put(worldName, position, gateId);
        }
    }

    public void removeAll(String worldName, List<Vector> positions) {
        if (positions == null) {
            return;
        }
        for (Vector position : positions) {
            remove(worldName, position);
        }
    }

    /**
     * Drop every cell currently attributed to the given gate in one world, regardless of where
     * those cells are. Used as a one-time defensive resync at animation completion, to correct
     * any drift the per-frame move() calls might have accumulated (e.g. under a lag-induced
     * frame skip) without needing to track each gate's exact previous cell set.
     */
    public void removeAllForGate(String worldName, int gateId) {
        Map<Long, Integer> cells = cellsByWorld.get(worldName);
        if (cells == null) {
            return;
        }
        cells.values().removeIf(id -> id == gateId);
        if (cells.isEmpty()) {
            cellsByWorld.remove(worldName);
        }
    }
}

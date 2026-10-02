package net.knightsandkings.knk.paper.roads;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

import org.bukkit.util.Vector;

import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.core.roads.build.GateCells;
import net.knightsandkings.knk.core.util.BlockKey;

/**
 * The gate-door cells of one world for the builder (DESIGN §5.4, plan D9 / R3): every door's <b>closed
 * footprint</b> ({@code GateManager.closedFootprint}, frame 0) mapped to its door id, so a road under a
 * gate builds the same whether the gate is open or closed. Built on the main thread from the gate
 * manager, read off-thread by {@code SpanGrid}.
 */
public final class GateCellsIndex implements GateCells {
    private final Map<Long, Integer> doorByCell;

    private GateCellsIndex(Map<Long, Integer> doorByCell) {
        this.doorByCell = doorByCell;
    }

    /** Every cached door of {@code world}. */
    public static GateCellsIndex of(GateManager gateManager, String world) {
        Objects.requireNonNull(world, "world");
        Map<Long, Integer> cells = new HashMap<>();
        if (gateManager != null) {
            for (CachedGateDoor door : gateManager.getAllGates().values()) {
                if (!world.equals(door.getWorldName())) {
                    continue;
                }
                for (Vector v : gateManager.closedFootprint(door.getId())) {
                    cells.put(BlockKey.pack(v.getBlockX(), v.getBlockY(), v.getBlockZ()), door.getId());
                }
            }
        }
        return new GateCellsIndex(cells);
    }

    public static GateCellsIndex empty() {
        return new GateCellsIndex(Map.of());
    }

    public int size() {
        return doorByCell.size();
    }

    /**
     * The doors with a footprint block inside the box (inclusive bounds) — the gates a walk search
     * there can meet (KNG-51 §6).
     */
    public Set<Integer> doorIdsWithin(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        Set<Integer> ids = new HashSet<>();
        doorByCell.forEach((key, id) -> {
            int x = BlockKey.x(key);
            int y = BlockKey.y(key);
            int z = BlockKey.z(key);
            if (x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ) {
                ids.add(id);
            }
        });
        return ids;
    }

    /** Same footprints, same doors (the walk capture cache drops a world's chunks when its gates changed). */
    @Override
    public boolean equals(Object o) {
        return o instanceof GateCellsIndex other && doorByCell.equals(other.doorByCell);
    }

    @Override
    public int hashCode() {
        return doorByCell.hashCode();
    }

    @Override
    public OptionalInt doorAt(int x, int y, int z) {
        Integer id = doorByCell.get(BlockKey.pack(x, y, z));
        return id == null ? OptionalInt.empty() : OptionalInt.of(id);
    }
}

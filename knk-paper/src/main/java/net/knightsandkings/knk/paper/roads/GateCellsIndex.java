package net.knightsandkings.knk.paper.roads;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

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

    @Override
    public OptionalInt doorAt(int x, int y, int z) {
        Integer id = doorByCell.get(BlockKey.pack(x, y, z));
        return id == null ? OptionalInt.empty() : OptionalInt.of(id);
    }
}

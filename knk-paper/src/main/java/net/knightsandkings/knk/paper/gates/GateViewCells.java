package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/**
 * Siege Phase 7b: a read-only window on a door's resting-frame blocks for code outside this package
 * (the siege non-member gate view sends them as per-player block changes). Delegates to
 * {@link GateRestingFramePlacer#restingFrameCells}, the same cell set the animation's resting frames
 * and the world-sync check use, so the view matches what the door really looks like at rest.
 */
public final class GateViewCells {
    private GateViewCells() {}

    /** One block of a resting frame. */
    public record ViewCell(Vector position, String blockData) {}

    /** The door's closed ({@code open = false}) or fully open resting-frame blocks. */
    public static List<ViewCell> restingCells(CachedGateDoor gate, boolean open, boolean rasterizationEnabled) {
        int frame = open ? gate.getAnimationDurationTicks() : 0;
        List<ViewCell> cells = new ArrayList<>();
        for (GateRestingFramePlacer.RestingCell cell : GateRestingFramePlacer.restingFrameCells(gate, frame, rasterizationEnabled)) {
            cells.add(new ViewCell(cell.position(), cell.blockData()));
        }
        return cells;
    }

    /** Parses a cell's block data like the placer does (full data string, else material name), else the fallback. */
    public static BlockData blockData(String blockData, Material fallback) {
        if (blockData != null && !blockData.isEmpty()) {
            try {
                return Bukkit.createBlockData(blockData);
            } catch (IllegalArgumentException e) {
                Material material = Material.matchMaterial(blockData);
                if (material != null && material.isBlock()) return material.createBlockData();
            }
        }
        return fallback.createBlockData();
    }
}

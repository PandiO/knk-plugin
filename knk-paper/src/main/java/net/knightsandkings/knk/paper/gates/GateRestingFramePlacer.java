package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.core.domain.gates.BlockSnapshot;
import net.knightsandkings.knk.core.domain.gates.CachedGate;
import net.knightsandkings.knk.core.gates.GateFrameCalculator;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared logic for what a gate's blocks actually look like at one of its two resting
 * (closed/open) endpoint frames - Mechanism 1's rasterized gap-fill and Mechanism 2's open-scan
 * pairing from docs/features/gate-structure-animation/ROTATION_GAP_FILL_DESIGN.md, both of which
 * only apply at these resting frames, never mid-swing (Decision 3).
 *
 * <p>Used by both {@link GateAnimationTask} (when an open/close animation completes) and
 * {@link GateStateSyncTask} (reconciling the world to a gate's already-resting DB state at server
 * startup) - both need the exact same "what should this gate's fully-open/closed footprint
 * actually look like" answer, not just the sparse per-block one.
 */
final class GateRestingFramePlacer {

    private GateRestingFramePlacer() {
    }

    /** One cell of a gate's resting-frame footprint: where, and with what blockdata. */
    record RestingCell(Vector position, String blockData) {
    }

    /**
     * Mechanism 1's trigger condition: only at a resting endpoint frame, only for a
     * diagonal-hinge ROTATION gate, only when no manual open-state scan exists (Mechanism 2
     * always wins outright over Mechanism 1), and only when the admin hasn't disabled the kill switch.
     */
    static boolean useRasterization(CachedGate gate, int frame, boolean rasterizationEnabled) {
        return rasterizationEnabled
            && (frame == 0 || frame == gate.getAnimationDurationTicks())
            && "ROTATION".equals(gate.getMotionType())
            && gate.getOpenBlocks().isEmpty()
            && gate.getSublatticeIndex() > 1;
    }

    /**
     * Every cell (position + blockdata) a gate's blocks actually occupy at a resting frame:
     * Mechanism 1's rasterized gap-fill when eligible, otherwise today's exact per-block set -
     * using a paired open-scan block's own blockdata as-is (Mechanism 2) where one exists.
     */
    static List<RestingCell> restingFrameCells(CachedGate gate, int frame, boolean rasterizationEnabled) {
        List<RestingCell> cells = new ArrayList<>();

        if (useRasterization(gate, frame, rasterizationEnabled)) {
            double angle = GateFrameCalculator.calculateRotationAngle(gate, frame);
            for (GateFrameCalculator.RasterizedBlock rasterized : GateFrameCalculator.rasterizeRotationFrame(gate, angle)) {
                String orientedBlockData = GateBlockOrientation.applyRotation(rasterized.sourceBlock().getBlockData(), gate, angle);
                cells.add(new RestingCell(rasterized.worldPosition(), orientedBlockData));
            }
            return cells;
        }

        double angle = GateFrameCalculator.calculateRotationAngle(gate, frame);
        for (BlockSnapshot block : gate.getBlocks()) {
            if (block == null) {
                continue;
            }

            Vector worldPos = GateFrameCalculator.calculateBlockPosition(gate, block, frame);
            if (worldPos == null) {
                continue;
            }

            BlockSnapshot pairedOpen = gate.getPairedOpenBlock(block.getId());
            String blockData = pairedOpen != null
                ? pairedOpen.getBlockData()
                : GateBlockOrientation.applyRotation(block.getBlockData(), gate, angle);
            cells.add(new RestingCell(worldPos, blockData));
        }
        return cells;
    }

    /** Force-places every cell of {@link #restingFrameCells}. */
    static void placeRestingFrame(World world, CachedGate gate, int frame, double angle,
                                   Material fallbackMaterial, boolean rasterizationEnabled) {
        for (RestingCell cell : restingFrameCells(gate, frame, rasterizationEnabled)) {
            GateBlockPlacer.placeBlock(world, cell.position(), cell.blockData(), fallbackMaterial);
        }
    }

    /** Just the positions from {@link #restingFrameCells}, for keeping GateSpatialIndex in sync. */
    static List<Vector> restingFramePositions(CachedGate gate, int frame, boolean rasterizationEnabled) {
        List<Vector> positions = new ArrayList<>();
        for (RestingCell cell : restingFrameCells(gate, frame, rasterizationEnabled)) {
            positions.add(cell.position());
        }
        return positions;
    }
}

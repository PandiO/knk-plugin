package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.core.domain.gates.BlockSnapshot;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.gates.GateFrameCalculator;
import net.knightsandkings.knk.core.gates.GateSpatialIndex;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

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

    private static final Logger LOGGER = Logger.getLogger(GateRestingFramePlacer.class.getName());

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
    static boolean useRasterization(CachedGateDoor gate, int frame, boolean rasterizationEnabled) {
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
    static List<RestingCell> restingFrameCells(CachedGateDoor gate, int frame, boolean rasterizationEnabled) {
        List<RestingCell> cells = new ArrayList<>();

        if (useRasterization(gate, frame, rasterizationEnabled)) {
            double angle = GateFrameCalculator.calculateRotationAngle(gate, frame);
            for (GateFrameCalculator.RasterizedBlock rasterized : GateFrameCalculator.rasterizeRotationFrame(gate, angle)) {
                String orientedBlockData = GateBlockOrientation.applyRotation(rasterized.sourceBlock().getBlockData(), gate, angle);
                cells.add(new RestingCell(rasterized.worldPosition(), orientedBlockData));
            }
            LOGGER.info("[GateRestingFrame] Gate '" + gate.getName() + "' (ID: " + gate.getId() + ") frame " + frame
                + " (angle=" + angle + "): rasterized " + cells.size() + " cell(s) from " + gate.getBlocks().size()
                + " scanned block(s) (sublatticeIndex=" + gate.getSublatticeIndex() + ").");
            return cells;
        }

        LOGGER.info("[GateRestingFrame] Gate '" + gate.getName() + "' (ID: " + gate.getId() + ") frame " + frame
            + ": rasterization NOT used (rasterizationEnabled=" + rasterizationEnabled + ", motionType="
            + gate.getMotionType() + ", openBlocks=" + gate.getOpenBlocks().size() + ", sublatticeIndex="
            + gate.getSublatticeIndex() + ") - using plain per-block placement.");

        double angle = GateFrameCalculator.calculateRotationAngle(gate, frame);
        // A paired block's own scanned open-state orientation is only authoritative at the door's
        // true OPEN resting frame - at the CLOSED resting frame (frame 0), the block's own scanned
        // closed-state orientation is correct instead, same as an unpaired block (fixed
        // 2026-09-15, alongside the matching per-tick swing fix in GateAnimationTask -
        // previously this used the paired open orientation at frame 0 too, showing a door's
        // closed-state blocks in their open-state look even while fully closed).
        boolean atOpenRestingFrame = frame == gate.getAnimationDurationTicks();
        for (BlockSnapshot block : gate.getBlocks()) {
            if (block == null) {
                continue;
            }

            Vector worldPos = GateFrameCalculator.calculateBlockPosition(gate, block, frame);
            if (worldPos == null) {
                continue;
            }

            BlockSnapshot pairedOpen = gate.getPairedOpenBlock(block.getId());
            String blockData = (pairedOpen != null && atOpenRestingFrame)
                ? pairedOpen.getBlockData()
                : GateBlockOrientation.applyRotation(block.getBlockData(), gate, angle);
            cells.add(new RestingCell(worldPos, blockData));
        }
        return cells;
    }

    /** Force-places every cell of {@link #restingFrameCells}. */
    static void placeRestingFrame(World world, CachedGateDoor gate, int frame, double angle,
                                   Material fallbackMaterial, boolean rasterizationEnabled) {
        for (RestingCell cell : restingFrameCells(gate, frame, rasterizationEnabled)) {
            GateBlockPlacer.placeBlock(world, cell.position(), cell.blockData(), fallbackMaterial);
        }
    }

    /**
     * Moves a gate from one resting frame to the other: clears whatever cells belonged only to
     * {@code fromFrame} (not also part of {@code toFrame}), then places {@code toFrame}.
     *
     * <p>Mechanism 1's rasterized gap-fill can place cells beyond the gate's own scanned
     * BlockSnapshots - extra filler blocks that exist only to make the diagonal surface look
     * solid. Those extra cells aren't tied to any single BlockSnapshot, so the ordinary per-tick
     * swing (which vacates each block's own previous position, one for one) never touches them,
     * and {@link #placeRestingFrame} only ever adds cells, never removes any - so without this,
     * an open gate's rasterized filler blocks would sit there forever after it closes again,
     * wherever they don't happen to be overwritten by the closed frame's own cells.
     */
    static void transitionRestingFrame(World world, CachedGateDoor gate, int fromFrame, int toFrame,
                                        Material fallbackMaterial, boolean rasterizationEnabled) {
        List<RestingCell> fromCells = restingFrameCells(gate, fromFrame, rasterizationEnabled);
        List<RestingCell> toCells = restingFrameCells(gate, toFrame, rasterizationEnabled);
        List<RestingCell> toClear = cellsToClear(fromCells, toCells);

        LOGGER.info("[GateRestingFrame] Gate '" + gate.getName() + "' (ID: " + gate.getId() + ") transitioning frame "
            + fromFrame + " -> " + toFrame + ": clearing " + toClear.size() + " cell(s), placing " + toCells.size()
            + " cell(s).");

        int clearedCount = 0;
        for (RestingCell cell : toClear) {
            if (GateBlockPlacer.removeBlockIfMatches(world, cell.position(), cell.blockData(), fallbackMaterial)) {
                clearedCount++;
            }
        }

        int placedCount = 0;
        for (RestingCell cell : toCells) {
            if (GateBlockPlacer.placeBlock(world, cell.position(), cell.blockData(), fallbackMaterial)) {
                placedCount++;
            }
        }

        if (clearedCount != toClear.size() || placedCount != toCells.size()) {
            LOGGER.warning("[GateRestingFrame] Gate '" + gate.getName() + "' (ID: " + gate.getId() + ") transition "
                + fromFrame + " -> " + toFrame + ": only cleared " + clearedCount + "/" + toClear.size()
                + " and placed " + placedCount + "/" + toCells.size() + " (chunk not loaded, or a mismatched "
                + "block already occupied the cell).");
        }
    }

    /**
     * Pure (World-free) half of {@link #transitionRestingFrame}: every {@code fromCells} cell
     * whose position isn't also occupied by {@code toCells} - i.e. the ones that need clearing,
     * not just overwriting, when moving from one resting frame to the other.
     */
    static List<RestingCell> cellsToClear(List<RestingCell> fromCells, List<RestingCell> toCells) {
        Set<Long> toPositions = new HashSet<>();
        for (RestingCell cell : toCells) {
            toPositions.add(GateSpatialIndex.packCell(cell.position()));
        }

        List<RestingCell> toClear = new ArrayList<>();
        for (RestingCell cell : fromCells) {
            if (!toPositions.contains(GateSpatialIndex.packCell(cell.position()))) {
                toClear.add(cell);
            }
        }
        return toClear;
    }

    /** Just the positions from {@link #restingFrameCells}, for keeping GateSpatialIndex in sync. */
    static List<Vector> restingFramePositions(CachedGateDoor gate, int frame, boolean rasterizationEnabled) {
        List<Vector> positions = new ArrayList<>();
        for (RestingCell cell : restingFrameCells(gate, frame, rasterizationEnabled)) {
            positions.add(cell.position());
        }
        return positions;
    }
}

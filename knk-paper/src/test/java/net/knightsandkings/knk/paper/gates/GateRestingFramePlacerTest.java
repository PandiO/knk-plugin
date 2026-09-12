package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.core.domain.gates.BlockSnapshot;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the pure (World-free) parts of GateRestingFramePlacer, shared by
 * GateAnimationTask (on animation completion) and GateStateSyncTask (startup reconciliation) -
 * see docs/features/gate-structure-animation/ROTATION_GAP_FILL_DESIGN.md.
 */
class GateRestingFramePlacerTest {

    private CachedGateDoor buildDiagonalDrawbridge(int width, int height) {
        CachedGateDoor gate = new CachedGateDoor(
            14, 14, "Diagonal Drawbridge", "DRAWBRIDGE", "ROTATION", "PLANE_GRID",
            90, 1,
            new Vector(0, 0, 0), width, height, 1,
            500.0, 500.0, true, false, true, 90,
            "south-east"
        );

        Vector uStep = new Vector(1, 0, 1);
        Vector vStep = new Vector(0, 1, 0);
        Vector nStep = new Vector(-1, 0, 1);
        gate.setUStep(uStep);
        gate.setVStep(vStep);
        gate.setNStep(nStep);
        gate.setHingeAxis(uStep);
        gate.setMotionVector(new Vector(0, 0, 0));
        gate.setSublatticeIndex(net.knightsandkings.knk.core.util.VectorMath.sublatticeIndex(uStep));

        int sortOrder = 0;
        for (int i = 0; i < width; i++) {
            for (int j = 0; j < height; j++) {
                Vector relPos = uStep.clone().multiply(i).add(vStep.clone().multiply(j));
                gate.addBlock(new BlockSnapshot(sortOrder, relPos, 1, "minecraft:oak_planks", sortOrder));
                sortOrder++;
            }
        }

        return gate;
    }

    @Test
    void useRasterization_DiagonalRotationGateAtEndpointWithNoOpenScan_IsEligible() {
        CachedGateDoor gate = buildDiagonalDrawbridge(8, 4);

        assertTrue(GateRestingFramePlacer.useRasterization(gate, 0, true));
        assertTrue(GateRestingFramePlacer.useRasterization(gate, gate.getAnimationDurationTicks(), true));
    }

    @Test
    void useRasterization_KillSwitchDisabled_NeverEligible() {
        CachedGateDoor gate = buildDiagonalDrawbridge(8, 4);
        assertFalse(GateRestingFramePlacer.useRasterization(gate, gate.getAnimationDurationTicks(), false));
    }

    @Test
    void useRasterization_MidSwingFrame_NeverEligible() {
        CachedGateDoor gate = buildDiagonalDrawbridge(8, 4);
        assertFalse(GateRestingFramePlacer.useRasterization(gate, 45, true));
    }

    @Test
    void useRasterization_OpenScanPresent_MechanismTwoWinsOutright() {
        CachedGateDoor gate = buildDiagonalDrawbridge(8, 4);
        gate.addOpenBlock(new BlockSnapshot(999, new Vector(0, 0, 0), 1, "minecraft:oak_planks", 0));

        assertFalse(GateRestingFramePlacer.useRasterization(gate, gate.getAnimationDurationTicks(), true));
    }

    @Test
    void useRasterization_CardinalGate_NeverEligible() {
        CachedGateDoor gate = new CachedGateDoor(
            15, 15, "Cardinal Drawbridge", "DRAWBRIDGE", "ROTATION", "PLANE_GRID",
            90, 1, new Vector(0, 0, 0), 4, 4, 1,
            500.0, 500.0, true, false, true, 90, "north"
        );
        Vector uStep = new Vector(1, 0, 0);
        gate.setUStep(uStep);
        gate.setVStep(new Vector(0, 1, 0));
        gate.setNStep(new Vector(0, 0, 1));
        gate.setHingeAxis(uStep);
        gate.setSublatticeIndex(net.knightsandkings.knk.core.util.VectorMath.sublatticeIndex(uStep));
        gate.addBlock(new BlockSnapshot(1, new Vector(0, 0, 0), 1, "minecraft:oak_planks", 0));

        assertFalse(GateRestingFramePlacer.useRasterization(gate, gate.getAnimationDurationTicks(), true));
    }

    // Note: restingFrameCells at a nonzero rotation angle calls GateBlockOrientation.applyRotation,
    // which needs a live Bukkit server (Bukkit.createBlockData) - out of scope for this plain unit
    // test class (see GateBlockScanTaskHandlerTest's similar note). The underlying "rasterization
    // fills more cells than the naive rotated set" property is already covered, Bukkit-free, by
    // GateFrameCalculatorTest.rasterizeRotationFrame_AtOpenAngle_FillsMoreCellsThanTheNaiveRotatedSet.

    @Test
    void restingFrameCells_RasterizationDisabledAtClosedFrame_MatchesRawBlockCount() {
        CachedGateDoor gate = buildDiagonalDrawbridge(8, 4);

        List<GateRestingFramePlacer.RestingCell> cells = GateRestingFramePlacer.restingFrameCells(gate, 0, false);

        assertEquals(gate.getBlocks().size(), cells.size());
    }

    @Test
    void restingFrameCells_PairedOpenBlock_UsesOpenBlockdataAsIs() {
        CachedGateDoor gate = new CachedGateDoor(
            16, 16, "Dual-Scan Drawbridge", "DRAWBRIDGE", "ROTATION", "PLANE_GRID",
            90, 1, new Vector(0, 0, 0), 1, 1, 1,
            500.0, 500.0, true, false, true, 90, "north"
        );
        Vector uStep = new Vector(1, 0, 0);
        gate.setUStep(uStep);
        gate.setVStep(new Vector(0, 1, 0));
        gate.setNStep(new Vector(0, 0, 1));
        gate.setHingeAxis(uStep);
        gate.setSublatticeIndex(1);
        gate.setOpenAnchorPoint(new Vector(50, 0, 50));

        BlockSnapshot closed = new BlockSnapshot(1, new Vector(0, 0, 0), 1, "minecraft:oak_log", 0);
        BlockSnapshot pairedOpen = new BlockSnapshot(2, new Vector(0, 0, 0), 1, "minecraft:oak_log[axis=z]", 0);
        gate.addBlock(closed);
        gate.setOpenBlockPairing(Map.of(1, pairedOpen));

        List<GateRestingFramePlacer.RestingCell> cells =
            GateRestingFramePlacer.restingFrameCells(gate, gate.getAnimationDurationTicks(), true);

        assertEquals(1, cells.size());
        assertEquals("minecraft:oak_log[axis=z]", cells.get(0).blockData());
    }

    @Test
    void cellsToClear_RasterizedExtraCellsNotInTargetFrame_AreReturned() {
        // Live-server bug: Mechanism 1's rasterized gap-fill places cells beyond the gate's own
        // scanned BlockSnapshots, but those extra cells aren't tied to any single BlockSnapshot,
        // so nothing ever cleared them when the gate closed again - they were left behind forever.
        // cellsToClear must surface exactly those "extra, not reused by the target frame" cells.
        //
        // Built directly from GateFrameCalculator.rasterizeRotationFrame (pure, Bukkit-free)
        // rather than restingFrameCells, since the latter's blockdata orientation step needs a
        // live Bukkit server for a nonzero angle (see this class's other tests' notes).
        CachedGateDoor gate = buildDiagonalDrawbridge(4, 8);

        List<GateRestingFramePlacer.RestingCell> openCells = toRestingCells(
            net.knightsandkings.knk.core.gates.GateFrameCalculator.rasterizeRotationFrame(gate, 90.0));
        List<GateRestingFramePlacer.RestingCell> closedCells = toRestingCells(
            net.knightsandkings.knk.core.gates.GateFrameCalculator.rasterizeRotationFrame(gate, 0.0));

        // The open frame's rasterized set is strictly larger than the raw scanned block count,
        // and essentially disjoint from the closed frame's footprint (a 90-degree swing away).
        assertTrue(openCells.size() > gate.getBlocks().size());

        List<GateRestingFramePlacer.RestingCell> toClear =
            GateRestingFramePlacer.cellsToClear(openCells, closedCells);

        assertFalse(toClear.isEmpty(), "Expected open-frame cells not reused by the closed frame to need clearing");
        assertTrue(toClear.size() >= gate.getBlocks().size(),
            "Expected at least as many cells to clear as there are scanned blocks, since the open "
                + "and closed footprints are essentially disjoint for a 90-degree swing");
    }

    private List<GateRestingFramePlacer.RestingCell> toRestingCells(
            List<net.knightsandkings.knk.core.gates.GateFrameCalculator.RasterizedBlock> rasterized) {
        return rasterized.stream()
            .map(block -> new GateRestingFramePlacer.RestingCell(block.worldPosition(), block.sourceBlock().getBlockData()))
            .toList();
    }

    @Test
    void cellsToClear_TargetFrameReusesSamePosition_IsNotReturned() {
        List<GateRestingFramePlacer.RestingCell> fromCells = List.of(
            new GateRestingFramePlacer.RestingCell(new Vector(0, 0, 0), "minecraft:oak_planks"),
            new GateRestingFramePlacer.RestingCell(new Vector(1, 0, 0), "minecraft:oak_planks"));
        List<GateRestingFramePlacer.RestingCell> toCells = List.of(
            new GateRestingFramePlacer.RestingCell(new Vector(0, 0, 0), "minecraft:stone"));

        List<GateRestingFramePlacer.RestingCell> toClear = GateRestingFramePlacer.cellsToClear(fromCells, toCells);

        assertEquals(1, toClear.size());
        assertEquals(new Vector(1, 0, 0), toClear.get(0).position());
    }
}

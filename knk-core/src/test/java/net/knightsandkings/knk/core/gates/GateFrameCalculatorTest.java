package net.knightsandkings.knk.core.gates;

import net.knightsandkings.knk.core.domain.gates.BlockSnapshot;
import net.knightsandkings.knk.core.domain.gates.CachedGate;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for GateFrameCalculator.
 */
class GateFrameCalculatorTest {

    private static final double EPSILON = 0.001;
    private CachedGate gate;

    @BeforeEach
    void setUp() {
        // Create a test gate with vertical motion
        gate = new CachedGate(
            1,                                      // id
            "TestGate",                             // name
            "SLIDING",                              // gateType
            "VERTICAL",                             // motionType
            "PLANE_GRID",                           // geometryDefinitionMode
            60,                                     // animationDurationTicks
            1,                                      // animationTickRate
            new Vector(100, 64, 100),               // anchorPoint
            5,                                      // geometryWidth
            5,                                      // geometryHeight
            3,                                      // geometryDepth (motion distance)
            500.0,                                  // healthCurrent
            500.0,                                  // healthMax
            true,                                   // isActive
            false,                                  // isDestroyed
            true,                                   // isInvincible
            90,                                     // rotationMaxAngleDegrees
            "north"                                // faceDirection
        );

        // Set up axes
        gate.setUAxis(new Vector(1, 0, 0));
        gate.setVAxis(new Vector(0, 1, 0));
        gate.setNAxis(new Vector(0, 0, 1));
        gate.setMotionVector(new Vector(0, 3, 0)); // Move up 3 blocks
    }

    @Test
    void shouldCalculateClosedPosition() {
        Vector relativePos = new Vector(0, 0, 0);
        BlockSnapshot block = new BlockSnapshot(1, relativePos, 1, "stone", 0);
        
        Vector position = GateFrameCalculator.calculateBlockPosition(gate, block, 0);
        
        assertEquals(100, position.getX(), EPSILON);
        assertEquals(64, position.getY(), EPSILON);
        assertEquals(100, position.getZ(), EPSILON);
    }

    @Test
    void shouldCalculateOpenPosition() {
        Vector relativePos = new Vector(0, 0, 0);
        BlockSnapshot block = new BlockSnapshot(1, relativePos, 1, "stone", 0);
        
        Vector position = GateFrameCalculator.calculateBlockPosition(gate, block, 60);
        
        assertEquals(100, position.getX(), EPSILON);
        assertEquals(67, position.getY(), EPSILON); // 64 + 3
        assertEquals(100, position.getZ(), EPSILON);
    }

    @Test
    void shouldCalculateMidpointPosition() {
        Vector relativePos = new Vector(0, 0, 0);
        BlockSnapshot block = new BlockSnapshot(1, relativePos, 1, "stone", 0);
        
        Vector position = GateFrameCalculator.calculateBlockPosition(gate, block, 30);
        
        assertEquals(100, position.getX(), EPSILON);
        assertEquals(65.5, position.getY(), EPSILON); // 64 + (3 * 0.5)
        assertEquals(100, position.getZ(), EPSILON);
    }

    @Test
    void shouldApplyRelativeOffset() {
        Vector relativePos = new Vector(1, 2, 0);
        BlockSnapshot block = new BlockSnapshot(1, relativePos, 1, "stone", 0);
        
        Vector position = GateFrameCalculator.calculateBlockPosition(gate, block, 0);
        
        assertEquals(101, position.getX(), EPSILON);
        assertEquals(66, position.getY(), EPSILON); // 64 + 2
        assertEquals(100, position.getZ(), EPSILON);
    }

    @Test
    void shouldClampFrameToValidRange() {
        Vector relativePos = new Vector(0, 0, 0);
        BlockSnapshot block = new BlockSnapshot(1, relativePos, 1, "stone", 0);
        
        // Frame too high
        Vector position1 = GateFrameCalculator.calculateBlockPosition(gate, block, 100);
        Vector position2 = GateFrameCalculator.calculateBlockPosition(gate, block, 60);
        
        assertEquals(position2.getX(), position1.getX(), EPSILON);
        assertEquals(position2.getY(), position1.getY(), EPSILON);
        assertEquals(position2.getZ(), position1.getZ(), EPSILON);
        
        // Frame negative
        Vector position3 = GateFrameCalculator.calculateBlockPosition(gate, block, -10);
        Vector position4 = GateFrameCalculator.calculateBlockPosition(gate, block, 0);
        
        assertEquals(position4.getX(), position3.getX(), EPSILON);
        assertEquals(position4.getY(), position3.getY(), EPSILON);
        assertEquals(position4.getZ(), position3.getZ(), EPSILON);
    }

    @Test
    void shouldCalculateStepVector() {
        Vector step = GateFrameCalculator.calculateStepVector(gate);
        
        assertEquals(0, step.getX(), EPSILON);
        assertEquals(0.05, step.getY(), EPSILON); // 3 / 60
        assertEquals(0, step.getZ(), EPSILON);
    }

    @Test
    void verticalClippingShouldKeepAllColumnsWhenReferencePointIsOffset() {
        CachedGate clippedGate = new CachedGate(
            12, "Offset Vertical Gate", "SLIDING", "VERTICAL", "PLANE_GRID",
            60, 1,
            new Vector(1416.699999988079, 65, -531.4505220512867),
            3, 8, 1,
            500.0, 500.0, true, false, true, 90,
            "south"
        );

        clippedGate.setUAxis(new Vector(-0.999957151538227, 0, 0.009257164120684738));
        clippedGate.setVAxis(new Vector(-0.0992015755606904, 0.992015772500933, -0.07787011310929316));
        clippedGate.setNAxis(new Vector(-0.009228107140689485, -0.07916991666000288, -0.996818421947873));
        clippedGate.setMotionVector(new Vector(0, 3, 0));
        clippedGate.setClipToGeometryBounds(true);

        assertNotNull(GateFrameCalculator.calculateBlockPosition(
            clippedGate,
            new BlockSnapshot(1, new Vector(-2, 2, 0), 1, "minecraft:spruce_fence", 0),
            60
        ));
        assertNotNull(GateFrameCalculator.calculateBlockPosition(
            clippedGate,
            new BlockSnapshot(2, new Vector(-1, 2, 0), 1, "minecraft:spruce_fence", 0),
            60
        ));
        assertNotNull(GateFrameCalculator.calculateBlockPosition(
            clippedGate,
            new BlockSnapshot(3, new Vector(0, 2, 0), 1, "minecraft:spruce_fence", 0),
            60
        ));
        assertNull(GateFrameCalculator.calculateBlockPosition(
            clippedGate,
            new BlockSnapshot(4, new Vector(0, 5, 0), 1, "minecraft:spruce_fence", 0),
            60
        ));
    }

    @Test
    void diagonalClippingShouldUseLatticeStepNotUnitAxis() {
        // A LATERAL gate whose u-axis is a 45-degree diagonal: uStep=(1,0,1) is the lattice step
        // (not the unit uAxis=(0.7071,0,0.7071)). Before the fix, isWithinGeometryBounds projected
        // onto the unit axis, so a lattice block's projection never landed on a clean integer index
        // and edge blocks were misclipped. This asserts the last in-bounds column (index 3 of
        // GeometryWidth=4) survives, and the first out-of-bounds column (index 4) is clipped.
        CachedGate diagonalGate = new CachedGate(
            4, "Diagonal Lateral Gate", "SLIDING", "LATERAL", "PLANE_GRID",
            60, 1,
            new Vector(0, 0, 0),
            4, 1, 1,
            500.0, 500.0, true, false, true, 90,
            "south-east"
        );

        diagonalGate.setUStep(new Vector(1, 0, 1));
        diagonalGate.setVStep(new Vector(0, 1, 0));
        diagonalGate.setNStep(new Vector(-1, 0, 1));
        diagonalGate.setMotionVector(new Vector(0, 0, 0));
        diagonalGate.setClipToGeometryBounds(true);

        assertNotNull(GateFrameCalculator.calculateBlockPosition(
            diagonalGate,
            new BlockSnapshot(1, new Vector(3, 0, 3), 1, "minecraft:iron_bars", 0),
            0
        ));
        assertNull(GateFrameCalculator.calculateBlockPosition(
            diagonalGate,
            new BlockSnapshot(2, new Vector(4, 0, 4), 1, "minecraft:iron_bars", 0),
            0
        ));
    }

    @Test
    void shouldCalculateAngleStep() {
        double angleStep = GateFrameCalculator.calculateAngleStep(gate);
        
        assertEquals(1.5, angleStep, EPSILON); // 90 / 60
    }

    @Test
    void shouldDetermineWhenToUpdateFrame() {
        // Always update first frame
        assertTrue(GateFrameCalculator.shouldUpdateFrame(gate, 0));
        
        // Always update last frame
        assertTrue(GateFrameCalculator.shouldUpdateFrame(gate, 60));
        
        // Update every tick (tickRate = 1)
        assertTrue(GateFrameCalculator.shouldUpdateFrame(gate, 30));
        assertTrue(GateFrameCalculator.shouldUpdateFrame(gate, 45));
    }

    @Test
    void shouldRespectTickRate() {
        CachedGate gateTickRate2 = new CachedGate(
            2, "TestGate2", "SLIDING", "VERTICAL", "PLANE_GRID",
            60, 2, // tickRate = 2
            new Vector(100, 64, 100), 5, 5, 3,
            500.0, 500.0, true, false, true, 90,
            "north"
        );

        assertTrue(GateFrameCalculator.shouldUpdateFrame(gateTickRate2, 0));
        assertTrue(GateFrameCalculator.shouldUpdateFrame(gateTickRate2, 60));
        assertTrue(GateFrameCalculator.shouldUpdateFrame(gateTickRate2, 30)); // 30 % 2 == 0
        assertFalse(GateFrameCalculator.shouldUpdateFrame(gateTickRate2, 25)); // 25 % 2 != 0
    }

    @Test
    void shouldCalculateRotationPosition() {
        CachedGate rotationGate = new CachedGate(
            3, "Drawbridge", "DRAWBRIDGE", "ROTATION", "PLANE_GRID",
            90, 1,
            new Vector(100, 64, 100), 0, 0, 0,
            500.0, 500.0, true, false, true, 90,
            "east"
        );
        
        rotationGate.setUAxis(new Vector(1, 0, 0));
        rotationGate.setVAxis(new Vector(0, 1, 0));
        rotationGate.setNAxis(new Vector(0, 0, 1));
        rotationGate.setHingeAxis(new Vector(0, 0, 1)); // Rotate around Z-axis
        rotationGate.setMotionVector(new Vector(0, 0, 0));

        Vector relativePos = new Vector(5, 0, 0); // 5 blocks forward
        BlockSnapshot block = new BlockSnapshot(1, relativePos, 1, "stone", 0);
        
        // At 0 frames: position should be at anchor + offset
        Vector pos0 = GateFrameCalculator.calculateBlockPosition(rotationGate, block, 0);
        assertEquals(105, pos0.getX(), EPSILON);
        assertEquals(64, pos0.getY(), EPSILON);
        
        // At 45 frames (50% of 90): 45 degrees rotation
        // Position should be rotated 45 degrees around Z-axis
        Vector pos45 = GateFrameCalculator.calculateBlockPosition(rotationGate, block, 45);

        // Rodrigues' formula around axis (0,0,1) for v=(5,0,0): rotated offset is
        // (5*cos(45), 5*sin(45), 0), added to the anchor (100, 64, 100).
        double offset = 5 * Math.cos(Math.toRadians(45));
        assertEquals(100 + offset, pos45.getX(), EPSILON);
        assertEquals(64 + offset, pos45.getY(), EPSILON);
        assertEquals(100, pos45.getZ(), EPSILON);
    }

    @Test
    void shouldCalculateRotationAngleAcrossFrameRange() {
        CachedGate rotationGate = new CachedGate(
            4, "Drawbridge", "DRAWBRIDGE", "ROTATION", "PLANE_GRID",
            90, 1,
            new Vector(100, 64, 100), 0, 0, 0,
            500.0, 500.0, true, false, true, 90,
            "east"
        );

        assertEquals(0.0, GateFrameCalculator.calculateRotationAngle(rotationGate, 0), EPSILON);
        assertEquals(45.0, GateFrameCalculator.calculateRotationAngle(rotationGate, 45), EPSILON);
        assertEquals(90.0, GateFrameCalculator.calculateRotationAngle(rotationGate, 90), EPSILON);

        // Frames are clamped to [0, totalFrames] the same way calculateBlockPosition clamps.
        assertEquals(90.0, GateFrameCalculator.calculateRotationAngle(rotationGate, 999), EPSILON);
        assertEquals(0.0, GateFrameCalculator.calculateRotationAngle(rotationGate, -10), EPSILON);
    }

    @Test
    void shouldReturnZeroRotationAngleForNonRotationGates() {
        // A VERTICAL/LATERAL gate's blocks must never be reoriented - even if
        // RotationMaxAngleDegrees happens to carry a nonzero leftover value in the DB.
        CachedGate verticalGate = new CachedGate(
            5, "Portcullis", "SLIDING", "VERTICAL", "PLANE_GRID",
            60, 1,
            new Vector(0, 64, 0), 0, 0, 0,
            500.0, 500.0, true, false, true, 90,
            "north"
        );

        assertEquals(0.0, GateFrameCalculator.calculateRotationAngle(verticalGate, 30), EPSILON);
        assertEquals(0.0, GateFrameCalculator.calculateRotationAngle(null, 30), EPSILON);
    }

    @Test
    void shouldHandleNullGate() {
        Vector relativePos = new Vector(0, 0, 0);
        BlockSnapshot block = new BlockSnapshot(1, relativePos, 1, "stone", 0);
        
        assertThrows(IllegalArgumentException.class, () -> {
            GateFrameCalculator.calculateBlockPosition(null, block, 0);
        });
    }

    @Test
    void shouldHandleNullBlock() {
        assertThrows(IllegalArgumentException.class, () -> {
            GateFrameCalculator.calculateBlockPosition(gate, null, 0);
        });
    }

    // === Mechanism 1: rasterizeRotationFrame (ROTATION_GAP_FILL_DESIGN.md) ===

    private CachedGate buildDiagonalDrawbridge(int width, int height) {
        CachedGate rotationGate = new CachedGate(
            14, "Diagonal Drawbridge", "DRAWBRIDGE", "ROTATION", "PLANE_GRID",
            90, 1,
            new Vector(0, 0, 0), width, height, 1,
            500.0, 500.0, true, false, true, 90,
            "south-east"
        );

        // 45-degree diagonal width axis: uStep and nStep both diagonal (index=2), vStep cardinal.
        Vector uStep = new Vector(1, 0, 1);
        Vector vStep = new Vector(0, 1, 0);
        Vector nStep = new Vector(-1, 0, 1);
        rotationGate.setUStep(uStep);
        rotationGate.setVStep(vStep);
        rotationGate.setNStep(nStep);
        rotationGate.setHingeAxis(uStep); // DRAWBRIDGE hinges on the width axis
        rotationGate.setMotionVector(new Vector(0, 0, 0));

        int sortOrder = 0;
        for (int i = 0; i < width; i++) {
            for (int j = 0; j < height; j++) {
                Vector relPos = uStep.clone().multiply(i).add(vStep.clone().multiply(j));
                rotationGate.addBlock(new BlockSnapshot(sortOrder, relPos, 1, "minecraft:oak_planks", sortOrder));
                sortOrder++;
            }
        }

        return rotationGate;
    }

    @Test
    void rasterizeRotationFrame_AtClosedAngle_ReproducesExactlyTheScannedGridNoMoreNoFewer() {
        CachedGate gate = buildDiagonalDrawbridge(8, 4);

        List<GateFrameCalculator.RasterizedBlock> rasterized = GateFrameCalculator.rasterizeRotationFrame(gate, 0.0);

        assertEquals(32, rasterized.size());
        for (GateFrameCalculator.RasterizedBlock block : rasterized) {
            assertTrue(gate.getBlocks().contains(block.sourceBlock()));
        }
    }

    @Test
    void rasterizeRotationFrame_AtOpenAngle_FillsMoreCellsThanTheNaiveRotatedSet() {
        CachedGate gate = buildDiagonalDrawbridge(8, 4);

        // The naive per-block approach (today's behavior without Mechanism 1) rotates each of the
        // 32 scanned points individually and floors each to its containing block - this is
        // exactly the reported bug: distinct scanned blocks can floor to the same cell (a
        // collision), and the true open footprint is larger than 32 cells to begin with, so the
        // naive approach's distinct cell count undercounts it. Rasterization must recover more of it.
        Set<String> naiveCells = new HashSet<>();
        for (BlockSnapshot block : gate.getBlocks()) {
            Vector pos = GateFrameCalculator.calculateBlockPosition(gate, block, gate.getAnimationDurationTicks());
            naiveCells.add(pos.getBlockX() + "," + pos.getBlockY() + "," + pos.getBlockZ());
        }

        List<GateFrameCalculator.RasterizedBlock> rasterized = GateFrameCalculator.rasterizeRotationFrame(gate, 90.0);

        assertTrue(rasterized.size() > naiveCells.size(),
            "Expected rasterization (" + rasterized.size() + ") to fill more cells than the naive "
                + "rotated-and-floored set (" + naiveCells.size() + ")");
        for (GateFrameCalculator.RasterizedBlock block : rasterized) {
            assertTrue(gate.getBlocks().contains(block.sourceBlock()));
        }
    }

    @Test
    void rasterizeRotationFrame_SingleCellGate_IsTrivialAtAnyAngle() {
        // Edge case from ROTATION_GAP_FILL_DESIGN.md: GeometryWidth/Height=1 has no gaps possible.
        CachedGate gate = buildDiagonalDrawbridge(1, 1);

        assertEquals(1, GateFrameCalculator.rasterizeRotationFrame(gate, 0.0).size());
        assertEquals(1, GateFrameCalculator.rasterizeRotationFrame(gate, 90.0).size());
    }

    @Test
    void rasterizeRotationFrame_NullGate_ReturnsEmptyList() {
        assertTrue(GateFrameCalculator.rasterizeRotationFrame(null, 45.0).isEmpty());
    }

    // === Mechanism 2: openBlockPairing blend/lerp (ROTATION_GAP_FILL_DESIGN.md) ===

    @Test
    void calculateBlockPosition_RotationWithPairedOpenBlock_ConvergesExactlyOnOpenScanPosition() {
        CachedGate rotationGate = new CachedGate(
            40, "Drawbridge", "DRAWBRIDGE", "ROTATION", "PLANE_GRID",
            90, 1,
            new Vector(100, 64, 100), 0, 0, 0,
            500.0, 500.0, true, false, true, 90,
            "east"
        );
        rotationGate.setHingeAxis(new Vector(0, 0, 1));
        rotationGate.setMotionVector(new Vector(0, 0, 0));
        rotationGate.setOpenAnchorPoint(new Vector(200, 64, 300));

        Vector relativePos = new Vector(5, 0, 0);
        BlockSnapshot closedBlock = new BlockSnapshot(7, relativePos, 1, "minecraft:oak_log", 0);
        // Deliberately far from where the pure arc would land, so the blend is actually exercised.
        BlockSnapshot openBlock = new BlockSnapshot(99, new Vector(-3, 1, 9), 1, "minecraft:oak_log[axis=z]", 0);
        rotationGate.setOpenBlockPairing(Map.of(7, openBlock));

        Vector expectedOpenWorldPos = rotationGate.getOpenAnchorPoint().clone().add(openBlock.getRelativePosition());

        // progress=0 -> unchanged from today's closed position.
        Vector atClosed = GateFrameCalculator.calculateBlockPosition(rotationGate, closedBlock, 0);
        assertEquals(105, atClosed.getX(), EPSILON);
        assertEquals(64, atClosed.getY(), EPSILON);
        assertEquals(100, atClosed.getZ(), EPSILON);

        // progress=1 -> converges exactly on the scanned open position, not the arc's own endpoint.
        Vector atOpen = GateFrameCalculator.calculateBlockPosition(rotationGate, closedBlock, 90);
        assertEquals(expectedOpenWorldPos.getX(), atOpen.getX(), EPSILON);
        assertEquals(expectedOpenWorldPos.getY(), atOpen.getY(), EPSILON);
        assertEquals(expectedOpenWorldPos.getZ(), atOpen.getZ(), EPSILON);
    }

    @Test
    void calculateBlockPosition_VerticalWithPairedOpenBlock_LerpsBetweenClosedAndOpenScanPositions() {
        CachedGate verticalGate = new CachedGate(
            41, "Portcullis", "SLIDING", "VERTICAL", "PLANE_GRID",
            60, 1,
            new Vector(0, 64, 0), 0, 0, 0,
            500.0, 500.0, true, false, true, 90,
            "north"
        );
        verticalGate.setMotionVector(new Vector(0, 5, 0));
        verticalGate.setOpenAnchorPoint(new Vector(10, 64, 0));

        BlockSnapshot closedBlock = new BlockSnapshot(1, new Vector(0, 0, 0), 1, "minecraft:iron_bars", 0);
        BlockSnapshot openBlock = new BlockSnapshot(2, new Vector(0, 0, 0), 1, "minecraft:iron_bars", 0);
        verticalGate.setOpenBlockPairing(Map.of(1, openBlock));

        Vector atClosed = GateFrameCalculator.calculateBlockPosition(verticalGate, closedBlock, 0);
        assertEquals(0, atClosed.getX(), EPSILON);
        assertEquals(64, atClosed.getY(), EPSILON);

        Vector atOpen = GateFrameCalculator.calculateBlockPosition(verticalGate, closedBlock, 60);
        assertEquals(10, atOpen.getX(), EPSILON);
        assertEquals(64, atOpen.getY(), EPSILON);

        Vector atHalf = GateFrameCalculator.calculateBlockPosition(verticalGate, closedBlock, 30);
        assertEquals(5, atHalf.getX(), EPSILON);
        assertEquals(64, atHalf.getY(), EPSILON);
    }

    @Test
    void calculateBlockPosition_UnpairedBlockOnGateWithOtherPairings_UsesOriginalProceduralPath() {
        // Decision 1(a): a block with no counterpart keeps today's exact original behavior,
        // unaffected by other blocks on the same gate having a pairing.
        CachedGate verticalGate = new CachedGate(
            42, "Portcullis", "SLIDING", "VERTICAL", "PLANE_GRID",
            60, 1,
            new Vector(0, 64, 0), 0, 0, 0,
            500.0, 500.0, true, false, true, 90,
            "north"
        );
        verticalGate.setMotionVector(new Vector(0, 5, 0));
        verticalGate.setOpenAnchorPoint(new Vector(10, 64, 0));
        verticalGate.setOpenBlockPairing(new HashMap<>(Map.of(1, new BlockSnapshot(2, new Vector(0, 0, 0), 1, "minecraft:iron_bars", 0))));

        BlockSnapshot unpairedBlock = new BlockSnapshot(3, new Vector(0, 0, 0), 1, "minecraft:iron_bars", 0);
        Vector atOpen = GateFrameCalculator.calculateBlockPosition(verticalGate, unpairedBlock, 60);

        assertEquals(0, atOpen.getX(), EPSILON);
        assertEquals(69, atOpen.getY(), EPSILON); // 64 + motionVector.y
        assertEquals(0, atOpen.getZ(), EPSILON);
    }
}

package net.knightsandkings.knk.core.gates;

import net.knightsandkings.knk.core.domain.gates.BlockSnapshot;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
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
    private CachedGateDoor gate;

    @BeforeEach
    void setUp() {
        // Create a test gate with vertical motion
        gate = new CachedGateDoor(
            1,                                      // id
            1,                                      // gateStructureId
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
        CachedGateDoor clippedGate = new CachedGateDoor(
            12, 12, "Offset Vertical Gate", "SLIDING", "VERTICAL", "PLANE_GRID",
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
        CachedGateDoor diagonalGate = new CachedGateDoor(
            4, 4, "Diagonal Lateral Gate", "SLIDING", "LATERAL", "PLANE_GRID",
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
        CachedGateDoor gateTickRate2 = new CachedGateDoor(
            2, 2, "TestGate2", "SLIDING", "VERTICAL", "PLANE_GRID",
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
        CachedGateDoor rotationGate = new CachedGateDoor(
            3, 3, "Drawbridge", "DRAWBRIDGE", "ROTATION", "PLANE_GRID",
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
        CachedGateDoor rotationGate = new CachedGateDoor(
            4, 4, "Drawbridge", "DRAWBRIDGE", "ROTATION", "PLANE_GRID",
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
        CachedGateDoor verticalGate = new CachedGateDoor(
            5, 5, "Portcullis", "SLIDING", "VERTICAL", "PLANE_GRID",
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

    private CachedGateDoor buildDiagonalDrawbridge(int width, int height) {
        CachedGateDoor rotationGate = new CachedGateDoor(
            14, 14, "Diagonal Drawbridge", "DRAWBRIDGE", "ROTATION", "PLANE_GRID",
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

    private CachedGateDoor buildRealGate14(int width, int height) {
        // Exact basis vectors from the live server's own log line for gate #14 (north-west
        // facing, not the south-east fixture used elsewhere in this file) - a sign-mirrored
        // variant of buildDiagonalDrawbridge, used to rule out a directionality-dependent bug.
        CachedGateDoor rotationGate = new CachedGateDoor(
            14, 14, "Northern Gate", "DRAWBRIDGE", "ROTATION", "PLANE_GRID",
            90, 1,
            new Vector(0, 0, 0), width, height, 0,
            2000.0, 2000.0, true, false, false, 90,
            "north-west"
        );

        Vector uStep = new Vector(-1, 0, 1);
        Vector vStep = new Vector(0, 1, 0);
        Vector nStep = new Vector(-1, 0, -1);
        rotationGate.setUStep(uStep);
        rotationGate.setVStep(vStep);
        rotationGate.setNStep(nStep);
        rotationGate.setHingeAxis(uStep);
        rotationGate.setMotionVector(new Vector(0, 0, 0));
        rotationGate.setSublatticeIndex(net.knightsandkings.knk.core.util.VectorMath.sublatticeIndex(uStep));

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
    void rasterizeRotationFrame_RealGate14AxesAtOpenAngle_EveryRowOfEveryColumnIsPresent() {
        // Regression test using gate #14's EXACT logged basis vectors (see GateAnimationTask's
        // "starting OPENING rotation" log line), not just a generic south-east fixture - an
        // earlier version of this fix passed the generic fixture but still left gate #14's own
        // edge column (u=width-1) short by one row live, because that column has fewer
        // neighboring cells for step 4's independent box-scan to recover a collision loser
        // through. See claimNearestAvailableCell in the fix itself.
        int width = 4;
        int height = 8;
        CachedGateDoor gate = buildRealGate14(width, height);
        Vector uStep = gate.getUStep();
        Vector vStep = gate.getVStep();

        List<GateFrameCalculator.RasterizedBlock> rasterized = GateFrameCalculator.rasterizeRotationFrame(gate, 90.0);

        Map<Integer, Set<Integer>> presentRowsByColumn = new HashMap<>();
        for (GateFrameCalculator.RasterizedBlock block : rasterized) {
            Vector relPos = block.sourceBlock().getRelativePosition();
            int u = (int) Math.round(relPos.dot(uStep) / uStep.lengthSquared());
            int v = (int) Math.round(relPos.dot(vStep) / vStep.lengthSquared());
            presentRowsByColumn.computeIfAbsent(u, k -> new HashSet<>()).add(v);
        }

        for (int u = 0; u < width; u++) {
            Set<Integer> presentRows = presentRowsByColumn.getOrDefault(u, Set.of());
            for (int v = 0; v < height; v++) {
                assertTrue(presentRows.contains(v),
                    "Column " + u + " is missing row " + v + " - present rows: " + presentRows);
            }
        }
    }

    @Test
    void rasterizeRotationFrame_AtClosedAngle_ReproducesExactlyTheScannedGridNoMoreNoFewer() {
        CachedGateDoor gate = buildDiagonalDrawbridge(8, 4);

        List<GateFrameCalculator.RasterizedBlock> rasterized = GateFrameCalculator.rasterizeRotationFrame(gate, 0.0);

        assertEquals(32, rasterized.size());
        for (GateFrameCalculator.RasterizedBlock block : rasterized) {
            assertTrue(gate.getBlocks().contains(block.sourceBlock()));
        }
    }

    @Test
    void rasterizeRotationFrame_AtOpenAngle_FillsMoreCellsThanTheNaiveRotatedSet() {
        CachedGateDoor gate = buildDiagonalDrawbridge(8, 4);

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
        CachedGateDoor gate = buildDiagonalDrawbridge(1, 1);

        assertEquals(1, GateFrameCalculator.rasterizeRotationFrame(gate, 0.0).size());
        assertEquals(1, GateFrameCalculator.rasterizeRotationFrame(gate, 90.0).size());
    }

    @Test
    void rasterizeRotationFrame_NullGate_ReturnsEmptyList() {
        assertTrue(GateFrameCalculator.rasterizeRotationFrame(null, 45.0).isEmpty());
    }

    @Test
    void rasterizeRotationFrame_AtOpenAngle_EveryRowOfEveryColumnIsPresent() {
        // Regression test for a live-server bug report on gate #14 itself (width=4, height=8,
        // south-east/45-degree hinge). Root cause: once a diagonal-hinge door swings to 90
        // degrees, consecutive height-rows are only 1/sqrt(2) of a block apart in world X/Z, so
        // several adjacent rows are mathematically guaranteed to round to the very same integer
        // cell - an intrinsic consequence of representing a continuously-rotated diagonal surface
        // with unit blocks. What *was* a genuine bug: the old code either let the row nearer the
        // hinge win that collision unconditionally (dropping the door's true, farthest reach -
        // live-observed as an 8-tall door only ever extending 6 blocks open), or, in a later
        // attempt, simply dropped whichever row lost a collision outright (worse at the
        // geometry's edge columns, which have fewer neighboring cells for step 4's independent
        // box-scan to patch the loss through - reproducing a milder "one column worse than the
        // rest" bug). Claiming the nearest still-free of the 8 floor/ceil corners around a row's
        // true position (see claimNearestAvailableCell) means every single row of every column
        // gets its own distinct cell.
        int width = 4;
        int height = 8;
        CachedGateDoor gate = buildDiagonalDrawbridge(width, height);
        Vector uStep = gate.getUStep();
        Vector vStep = gate.getVStep();

        List<GateFrameCalculator.RasterizedBlock> rasterized = GateFrameCalculator.rasterizeRotationFrame(gate, 90.0);

        Map<Integer, Set<Integer>> presentRowsByColumn = new HashMap<>();
        for (GateFrameCalculator.RasterizedBlock block : rasterized) {
            Vector relPos = block.sourceBlock().getRelativePosition();
            int u = (int) Math.round(relPos.dot(uStep) / uStep.lengthSquared());
            int v = (int) Math.round(relPos.dot(vStep) / vStep.lengthSquared());
            presentRowsByColumn.computeIfAbsent(u, k -> new HashSet<>()).add(v);
        }

        for (int u = 0; u < width; u++) {
            Set<Integer> presentRows = presentRowsByColumn.getOrDefault(u, Set.of());
            for (int v = 0; v < height; v++) {
                assertTrue(presentRows.contains(v),
                    "Column " + u + " is missing row " + v + " - present rows: " + presentRows);
            }
        }
    }

    // === Mechanism 2 / item 6.10's uniform rigid transform (ROTATION_GAP_FILL_DESIGN.md, Decision 7) ===

    private CachedGateDoor buildRotationGateForBlend() {
        CachedGateDoor rotationGate = new CachedGateDoor(
            40, 40, "Drawbridge", "DRAWBRIDGE", "ROTATION", "PLANE_GRID",
            90, 1,
            new Vector(100, 64, 100), 0, 0, 0,
            500.0, 500.0, true, false, true, 90,
            "east"
        );
        rotationGate.setHingeAxis(new Vector(0, 0, 1));
        rotationGate.setMotionVector(new Vector(0, 0, 0));
        rotationGate.setOpenAnchorPoint(new Vector(200, 64, 300));
        return rotationGate;
    }

    @Test
    void calculateBlockPosition_RotationWithFittedTransform_ConvergesExactlyOnOpenScanPositionForPairedBlock() {
        // Item 6.10: with a real rigid transform available (>=3 non-collinear pairs, fit
        // separately/tested in RigidTransformTest), a paired block's mid-swing correction now
        // converges toward the SHARED transform's own prediction for its closed-frame position,
        // not its individually-nearest-neighbor-matched target - but for a perfectly self-
        // consistent (noise-free) dataset like this one, those are the same value, so the same
        // progress=0/progress=1 exactness guarantee as the old per-block scheme still holds.
        CachedGateDoor rotationGate = buildRotationGateForBlend();

        Vector relativePos = new Vector(5, 0, 0);
        BlockSnapshot closedBlock = new BlockSnapshot(7, relativePos, 1, "minecraft:oak_log", 0);

        // A consistent rigid mapping (rotate 30 degrees around Y, then translate) applied to 3
        // non-collinear closed-frame points, including the block under test - so RigidTransform.fit
        // recovers this transform exactly, and closedBlock's own predicted position under the fit
        // exactly equals its real open-scan position.
        Vector axis = new Vector(0, 1, 0);
        double angleDegrees = 30.0;
        Vector translation = new Vector(50, 0, 150);
        List<Vector> closedRelPositions = List.of(new Vector(5, 0, 0), new Vector(0, 0, 0), new Vector(3, 0, 4));
        List<Vector> closedWorldPositions = new java.util.ArrayList<>();
        List<Vector> openWorldPositions = new java.util.ArrayList<>();
        for (Vector rel : closedRelPositions) {
            Vector closedWorld = rotationGate.getAnchorPoint().clone().add(rel);
            closedWorldPositions.add(closedWorld);
            openWorldPositions.add(net.knightsandkings.knk.core.util.VectorMath
                .rotateAroundAxis(closedWorld, axis, angleDegrees).add(translation));
        }
        RigidTransform transform = RigidTransform.fit(closedWorldPositions, openWorldPositions);
        assertNotNull(transform, "3 non-collinear pairs must produce a fit");
        rotationGate.setFittedOpenTransform(transform);

        Vector expectedOpenWorldPos = transform.apply(rotationGate.getAnchorPoint().clone().add(relativePos));

        // progress=0 -> unchanged from today's closed position.
        Vector atClosed = GateFrameCalculator.calculateBlockPosition(rotationGate, closedBlock, 0);
        assertEquals(105, atClosed.getX(), EPSILON);
        assertEquals(64, atClosed.getY(), EPSILON);
        assertEquals(100, atClosed.getZ(), EPSILON);

        // progress=1 -> converges exactly on the transform's prediction (== the real open-scan
        // position, for this noise-free dataset), not the pure arc's own endpoint.
        Vector atOpen = GateFrameCalculator.calculateBlockPosition(rotationGate, closedBlock, 90);
        assertEquals(expectedOpenWorldPos.getX(), atOpen.getX(), EPSILON);
        assertEquals(expectedOpenWorldPos.getY(), atOpen.getY(), EPSILON);
        assertEquals(expectedOpenWorldPos.getZ(), atOpen.getZ(), EPSILON);
    }

    @Test
    void calculateBlockPosition_RotationWithNoFittedTransform_FallsBackToPureProceduralRotationEvenIfPaired() {
        // Decision 7's degenerate fallback: too few (or collinear) correspondence pairs to fit a
        // rigid transform means "nothing to fit" - the ROTATION mid-swing formula must be
        // byte-for-byte today's pure procedural rotation, ignoring any individual per-block
        // pairing entirely (unlike the old Mechanism 2 scheme, which still blended toward the
        // pairing even with just one correspondence).
        CachedGateDoor rotationGate = buildRotationGateForBlend();
        assertNull(rotationGate.getFittedOpenTransform());

        Vector relativePos = new Vector(5, 0, 0);
        BlockSnapshot closedBlock = new BlockSnapshot(7, relativePos, 1, "minecraft:oak_log", 0);
        BlockSnapshot openBlock = new BlockSnapshot(99, new Vector(-3, 1, 9), 1, "minecraft:oak_log[axis=z]", 0);
        rotationGate.setOpenBlockPairing(Map.of(7, openBlock));

        Vector atOpen = GateFrameCalculator.calculateBlockPosition(rotationGate, closedBlock, 90);
        Vector pureArc = GateFrameCalculator.calculateBlockPosition(buildRotationGateForBlend(), closedBlock, 90);
        assertEquals(pureArc.getX(), atOpen.getX(), EPSILON);
        assertEquals(pureArc.getY(), atOpen.getY(), EPSILON);
        assertEquals(pureArc.getZ(), atOpen.getZ(), EPSILON);
    }

    @Test
    void calculateOpenOnlyBlockPosition_WithFittedTransform_StartsSynthesizedAndConvergesExactlyOnRealOpenPosition() {
        // Item 6.10 step 3: a block that only exists in the open scan (no closed-side pairing at
        // all) gets a synthesized closed-frame start point (the inverse of the fitted transform
        // applied to its real open-scan position), then animates via the exact same blend as a
        // real paired block - converging exactly onto its own real scanned open position at
        // progress=1, and starting from the synthesized point (not "nowhere"/null) at progress=0.
        CachedGateDoor rotationGate = buildRotationGateForBlend();

        Vector axis = new Vector(0, 1, 0);
        double angleDegrees = 30.0;
        Vector translation = new Vector(50, 0, 150);
        List<Vector> closedRelPositions = List.of(new Vector(5, 0, 0), new Vector(0, 0, 0), new Vector(3, 0, 4));
        List<Vector> closedWorldPositions = new java.util.ArrayList<>();
        List<Vector> openWorldPositions = new java.util.ArrayList<>();
        for (Vector rel : closedRelPositions) {
            Vector closedWorld = rotationGate.getAnchorPoint().clone().add(rel);
            closedWorldPositions.add(closedWorld);
            openWorldPositions.add(net.knightsandkings.knk.core.util.VectorMath
                .rotateAroundAxis(closedWorld, axis, angleDegrees).add(translation));
        }
        RigidTransform transform = RigidTransform.fit(closedWorldPositions, openWorldPositions);
        assertNotNull(transform);
        rotationGate.setFittedOpenTransform(transform);

        // The open-only block's own real scanned position - deliberately NOT one of the 3 fit
        // correspondences above, so this exercises genuine extrapolation via the shared transform.
        BlockSnapshot openOnlyBlock = new BlockSnapshot(200, new Vector(9, 0, 1), 1, "minecraft:oak_planks", 0);
        Vector openWorldPos = rotationGate.getOpenAnchorPoint().clone().add(openOnlyBlock.getRelativePosition());
        Vector synthesizedClosedWorldPos = transform.applyInverse(openWorldPos);
        Vector synthesizedRelativePos = synthesizedClosedWorldPos.clone().subtract(rotationGate.getAnchorPoint());
        rotationGate.setOpenOnlyBlockSynthesizedRelativePositions(Map.of(openOnlyBlock.getId(), synthesizedRelativePos));

        Vector atClosed = GateFrameCalculator.calculateOpenOnlyBlockPosition(rotationGate, openOnlyBlock, 0);
        assertEquals(synthesizedClosedWorldPos.getX(), atClosed.getX(), EPSILON);
        assertEquals(synthesizedClosedWorldPos.getY(), atClosed.getY(), EPSILON);
        assertEquals(synthesizedClosedWorldPos.getZ(), atClosed.getZ(), EPSILON);

        Vector atOpen = GateFrameCalculator.calculateOpenOnlyBlockPosition(rotationGate, openOnlyBlock, 90);
        assertEquals(openWorldPos.getX(), atOpen.getX(), EPSILON);
        assertEquals(openWorldPos.getY(), atOpen.getY(), EPSILON);
        assertEquals(openWorldPos.getZ(), atOpen.getZ(), EPSILON);
    }

    @Test
    void calculateOpenOnlyBlockPosition_WithNoFittedTransform_ReturnsNullFinalPosition() {
        // Without a fit, an open-only block has no mid-swing path at all - matching item 6.9's
        // original, still-correct "pop in at the open resting frame only" behavior for this case.
        CachedGateDoor rotationGate = buildRotationGateForBlend();
        BlockSnapshot openOnlyBlock = new BlockSnapshot(201, new Vector(1, 0, 1), 1, "minecraft:oak_planks", 0);

        assertNull(GateFrameCalculator.calculateOpenOnlyBlockPosition(rotationGate, openOnlyBlock, 45));
    }

    @Test
    void calculateBlockPosition_VerticalWithPairedOpenBlock_LerpsBetweenClosedAndOpenScanPositions() {
        CachedGateDoor verticalGate = new CachedGateDoor(
            41, 41, "Portcullis", "SLIDING", "VERTICAL", "PLANE_GRID",
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
        CachedGateDoor verticalGate = new CachedGateDoor(
            42, 42, "Portcullis", "SLIDING", "VERTICAL", "PLANE_GRID",
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

    // === Item 6.11: hybrid rigid-transform-plus-residual (ROTATION_GAP_FILL_DESIGN.md, Decision 8) ===

    private RigidTransform fitExactRotationTransform(CachedGateDoor gate, Vector axis, double angleDegrees, Vector translation) {
        List<Vector> closedRelPositions = List.of(new Vector(5, 0, 0), new Vector(0, 0, 0), new Vector(3, 0, 4));
        List<Vector> closedWorldPositions = new java.util.ArrayList<>();
        List<Vector> openWorldPositions = new java.util.ArrayList<>();
        for (Vector rel : closedRelPositions) {
            Vector closedWorld = gate.getAnchorPoint().clone().add(rel);
            closedWorldPositions.add(closedWorld);
            openWorldPositions.add(net.knightsandkings.knk.core.util.VectorMath
                .rotateAroundAxis(closedWorld, axis, angleDegrees).add(translation));
        }
        return RigidTransform.fit(closedWorldPositions, openWorldPositions);
    }

    @Test
    void calculateBlockPositionBreakdown_RotationWithExactFit_ResidualIsAlwaysZero() {
        // Regression: when a paired block's real open position exactly matches the shared
        // transform's prediction for it (transformPos == openTarget - the "everything is well-fit"
        // case), item 6.11's residual must be exactly zero at EVERY frame, not just the endpoints -
        // i.e. the hybrid must be byte-for-byte identical to item 6.10's plain uniform-transform
        // formula whenever there's nothing for the residual to correct.
        CachedGateDoor rotationGate = buildRotationGateForBlend();
        Vector axis = new Vector(0, 1, 0);
        double angleDegrees = 30.0;
        Vector translation = new Vector(50, 0, 150);
        RigidTransform transform = fitExactRotationTransform(rotationGate, axis, angleDegrees, translation);
        assertNotNull(transform);
        rotationGate.setFittedOpenTransform(transform);

        Vector relativePos = new Vector(5, 0, 0); // one of the 3 exact-fit correspondence points
        BlockSnapshot closedBlock = new BlockSnapshot(7, relativePos, 1, "minecraft:oak_log", 0);
        Vector openWorldPos = net.knightsandkings.knk.core.util.VectorMath
            .rotateAroundAxis(rotationGate.getAnchorPoint().clone().add(relativePos), axis, angleDegrees).add(translation);
        BlockSnapshot openBlock = new BlockSnapshot(99, openWorldPos.clone().subtract(rotationGate.getOpenAnchorPoint()), 1, "minecraft:oak_log", 0);
        rotationGate.setOpenBlockPairing(Map.of(7, openBlock));

        for (int frame : new int[]{0, 10, 45, 80, 90}) {
            GateFrameCalculator.BlockPositionBreakdown breakdown =
                GateFrameCalculator.calculateBlockPositionBreakdown(rotationGate, closedBlock, frame);
            assertNotNull(breakdown.residual(), "residual must still be present (a pairing exists), just zero-length");
            assertEquals(0.0, breakdown.residual().length(), EPSILON, "frame " + frame);
        }
    }

    @Test
    void calculateBlockPosition_RotationWithMismatchedPairing_ConvergesExactlyAndDoesNotCollapseToTheOldFormula() {
        // Decision 8's core regression: a genuine outlier block (its real paired position is far
        // from what the shared transform predicts for it - mirroring the live-observed 5.7-block
        // mismatch on entity 14's block 1119) must still land EXACTLY on its own real position at
        // progress=1 (no collision-risk residual left uncorrected), while NOT simply reproducing
        // the old, pre-6.10 per-block-blend formula at intermediate frames - the exact pitfall
        // Decision 8 warns a naive (linear-taper) residual implementation would fall into.
        CachedGateDoor rotationGate = buildRotationGateForBlend();
        Vector axis = new Vector(0, 1, 0);
        double angleDegrees = 30.0;
        Vector translation = new Vector(50, 0, 150);
        RigidTransform transform = fitExactRotationTransform(rotationGate, axis, angleDegrees, translation);
        assertNotNull(transform);
        rotationGate.setFittedOpenTransform(transform);

        // A block NOT among the 3 fit correspondences, deliberately paired to a wildly mismatched
        // real open position - the transform's own prediction for it is nowhere near this.
        Vector outlierRelativePos = new Vector(10, 0, 2);
        BlockSnapshot outlierClosedBlock = new BlockSnapshot(50, outlierRelativePos, 1, "minecraft:oak_log", 0);
        Vector mismatchedOpenWorldPos = new Vector(9999, 64, 9999);
        BlockSnapshot outlierOpenBlock = new BlockSnapshot(51,
            mismatchedOpenWorldPos.clone().subtract(rotationGate.getOpenAnchorPoint()), 1, "minecraft:oak_log", 0);
        rotationGate.setOpenBlockPairing(Map.of(50, outlierOpenBlock));

        // progress=0 -> exact closed position, unaffected by the (huge) residual.
        Vector atClosed = GateFrameCalculator.calculateBlockPosition(rotationGate, outlierClosedBlock, 0);
        Vector expectedClosed = rotationGate.getAnchorPoint().clone().add(outlierRelativePos);
        assertEquals(expectedClosed.getX(), atClosed.getX(), EPSILON);
        assertEquals(expectedClosed.getY(), atClosed.getY(), EPSILON);
        assertEquals(expectedClosed.getZ(), atClosed.getZ(), EPSILON);

        // progress=1 -> exact real (mismatched) open position - this is what prevents the
        // collision/JAM Decision 8 documents: every paired block still ends up exactly where it
        // was really scanned, not at the sparse transform's rotated-copy prediction.
        Vector atOpen = GateFrameCalculator.calculateBlockPosition(rotationGate, outlierClosedBlock, 90);
        assertEquals(mismatchedOpenWorldPos.getX(), atOpen.getX(), EPSILON);
        assertEquals(mismatchedOpenWorldPos.getY(), atOpen.getY(), EPSILON);
        assertEquals(mismatchedOpenWorldPos.getZ(), atOpen.getZ(), EPSILON);

        // Intermediate frame (progress=0.5) must NOT match the naive linear-taper/old-formula
        // result - the algebraic-cancellation pitfall Decision 8 documents explicitly.
        int frame = 45;
        double progress = 0.5;
        GateFrameCalculator.BlockPositionBreakdown breakdown =
            GateFrameCalculator.calculateBlockPositionBreakdown(rotationGate, outlierClosedBlock, frame);
        Vector actual = breakdown.finalPosition();

        // Reconstruct the OLD (pre-6.10) formula's result independently via a gate with NO fitted
        // transform, whose ROTATION branch reduces to the pure arc (calculateRotationPosition is
        // private, so this is the black-box way to get arcPos/arcPosFinal for the comparison).
        CachedGateDoor pureArcGate = buildRotationGateForBlend();
        Vector arcPos = GateFrameCalculator.calculateBlockPosition(pureArcGate, outlierClosedBlock, frame);
        Vector arcPosFinal = GateFrameCalculator.calculateBlockPosition(pureArcGate, outlierClosedBlock, 90);
        Vector naiveLinearResult = arcPos.clone().add(mismatchedOpenWorldPos.clone().subtract(arcPosFinal).multiply(progress));

        double distanceFromNaive = actual.clone().subtract(naiveLinearResult).length();
        assertTrue(distanceFromNaive > 1.0,
            "Hybrid's intermediate position must differ meaningfully from the naive linear-taper "
                + "(old Mechanism 2) formula - got distance " + distanceFromNaive);

        // And the residual's actual contribution at progress=0.5 should be a small fraction of its
        // full (progress=1) magnitude - the cubic taper's "stays visually rigid for most of the
        // swing" property, checked numerically rather than just claimed.
        Vector transformPos = transform.apply(rotationGate.getAnchorPoint().clone().add(outlierRelativePos));
        double fullResidualMagnitude = mismatchedOpenWorldPos.clone().subtract(transformPos).length();
        double residualAtHalf = breakdown.residual().length();
        assertTrue(residualAtHalf < fullResidualMagnitude * 0.2,
            "Residual at progress=0.5 should be a small fraction of its full magnitude (cubic taper: "
                + "0.5^3=0.125), got " + residualAtHalf + " vs full " + fullResidualMagnitude);
    }

    @Test
    void calculateBlockPosition_RotationBlockConvergingToTarget_SnapsExactlyOnceWithinSnapDistance() {
        // Item 6.11.2 (Decision 8, second follow-up): item 6.11.1's first attempt (a fixed
        // progress threshold) turned out not to work - live testing showed the frame at which a
        // collision risk actually materializes isn't a fixed fraction of the swing (frame 89 for
        // one colliding pair, frame 84 for a different pair, on the very same door). The real fix
        // is distance-based: once a block's blend has naturally converged to within SNAP_DISTANCE
        // of its own real, by-definition-distinct target, it must snap to that target EXACTLY
        // (never just "close") - close enough that Math.floor() cannot mistake it for a different
        // real block's cell. Verified here empirically (walking every frame, not hand-predicting a
        // boundary - the whole point is that the boundary isn't reliably predictable by a simple
        // formula) rather than assuming a specific frame.
        // Deliberately NOT buildRotationGateForBlend()'s usual fixture: its anchor is far from the
        // world origin, and VectorMath.rotateAroundAxis rotates around the origin - combined with
        // that fixture's large (50,0,150) fit translation, the resulting uniform-correction term
        // ends up over 100 blocks for any test point, which swamps the small, realistic residual
        // this test needs to isolate. An anchor near the origin keeps all the blend's terms at a
        // believable, real-geometry scale instead.
        CachedGateDoor rotationGate = new CachedGateDoor(
            41, 41, "Drawbridge", "DRAWBRIDGE", "ROTATION", "PLANE_GRID",
            90, 1,
            new Vector(0, 64, 0), 0, 0, 0,
            500.0, 500.0, true, false, true, 90,
            "east"
        );
        rotationGate.setHingeAxis(new Vector(0, 0, 1));
        rotationGate.setMotionVector(new Vector(0, 0, 0));
        rotationGate.setOpenAnchorPoint(new Vector(0, 64, 0));

        Vector axis = new Vector(0, 1, 0);
        double angleDegrees = 30.0;
        Vector translation = new Vector(5, 0, 3);
        RigidTransform transform = fitExactRotationTransform(rotationGate, axis, angleDegrees, translation);
        assertNotNull(transform);
        rotationGate.setFittedOpenTransform(transform);

        Vector outlierRelativePos = new Vector(2, 0, 1);
        BlockSnapshot outlierClosedBlock = new BlockSnapshot(50, outlierRelativePos, 1, "minecraft:oak_log", 0);
        // A moderate, realistic-scale mismatch (a couple of blocks, matching the live-observed
        // correctionMag range) rather than the astronomical one used elsewhere in this file to
        // isolate pure endpoint algebra - this one needs to actually cross the snap threshold
        // partway through a normal swing, the way it does in practice. Offset from the shared
        // transform's OWN prediction for this block (not from the anchor).
        Vector transformPosForOutlier = transform.apply(rotationGate.getAnchorPoint().clone().add(outlierRelativePos));
        Vector mismatchedOpenWorldPos = transformPosForOutlier.clone().add(new Vector(2, 0, 1));
        BlockSnapshot outlierOpenBlock = new BlockSnapshot(51,
            mismatchedOpenWorldPos.clone().subtract(rotationGate.getOpenAnchorPoint()), 1, "minecraft:oak_log", 0);
        rotationGate.setOpenBlockPairing(Map.of(50, outlierOpenBlock));

        int totalFrames = rotationGate.getAnimationDurationTicks();
        int firstSnappedFrame = -1;
        for (int frame = 0; frame <= totalFrames; frame++) {
            Vector position = GateFrameCalculator.calculateBlockPosition(rotationGate, outlierClosedBlock, frame);
            double distance = position.clone().subtract(mismatchedOpenWorldPos).length();
            if (distance < EPSILON) {
                firstSnappedFrame = frame;
                break;
            }
        }

        assertTrue(firstSnappedFrame >= 0 && firstSnappedFrame < totalFrames,
            "block should snap to its exact target strictly before the true final frame (frame "
                + totalFrames + "), found: " + firstSnappedFrame);

        // Once snapped, it must stay exactly there for every subsequent frame - no un-snapping,
        // no drift, matching the "holding, motionless" property the fix is meant to provide.
        for (int frame = firstSnappedFrame; frame <= totalFrames; frame++) {
            Vector position = GateFrameCalculator.calculateBlockPosition(rotationGate, outlierClosedBlock, frame);
            double distance = position.clone().subtract(mismatchedOpenWorldPos).length();
            assertEquals(0.0, distance, EPSILON, "frame " + frame);
        }

        // The frame immediately before the snap must NOT already be exactly at the target -
        // proving this is a genuine distance-triggered transition, not the formula trivially being
        // exact everywhere.
        if (firstSnappedFrame > 0) {
            Vector beforeSnap = GateFrameCalculator.calculateBlockPosition(rotationGate, outlierClosedBlock, firstSnappedFrame - 1);
            double distanceBeforeSnap = beforeSnap.clone().subtract(mismatchedOpenWorldPos).length();
            assertTrue(distanceBeforeSnap > EPSILON,
                "frame " + (firstSnappedFrame - 1) + " (right before the snap) should not yet be exactly at target");
        }
    }
}

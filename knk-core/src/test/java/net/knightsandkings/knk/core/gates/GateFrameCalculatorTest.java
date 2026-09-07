package net.knightsandkings.knk.core.gates;

import net.knightsandkings.knk.core.domain.gates.BlockSnapshot;
import net.knightsandkings.knk.core.domain.gates.CachedGate;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
}

package net.knightsandkings.knk.paper.gates;

import org.bukkit.Axis;
import org.bukkit.block.BlockFace;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for the pure rotate-and-snap vector math in GateBlockOrientation.
 * These exercise rotateAxis/rotateFace directly (no Bukkit.createBlockData/server access
 * involved) so they run without a live server or MockBukkit.
 */
class GateBlockOrientationTest {

    @Test
    void rotateAxis_SwingsVerticalLogHorizontalAroundACardinalHinge() {
        // A cardinal (east-west) hinge, like a non-diagonal drawbridge: a vertically-placed log
        // (axis=Y) should end up lying flat along Z once rotated a full 90 degrees.
        Vector hingeAxis = new Vector(1, 0, 0);

        assertEquals(Axis.Y, GateBlockOrientation.rotateAxis(Axis.Y, hingeAxis, 0));
        assertEquals(Axis.Z, GateBlockOrientation.rotateAxis(Axis.Y, hingeAxis, 90));
        // The hinge's own axis component never changes under rotation - X stays X regardless
        // of angle.
        assertEquals(Axis.X, GateBlockOrientation.rotateAxis(Axis.X, hingeAxis, 90));
    }

    @Test
    void rotateAxis_SnapsDeterministicallyForADiagonalHinge() {
        // Gate 14's actual hinge axis: rotating a vertical log 90 degrees around a diagonal
        // hinge lands exactly between X and Z (both components equal magnitude) - there is no
        // single "correct" cardinal axis for a genuinely diagonal result, so this only pins down
        // that the tie-break is deterministic (X preferred), not that it's the "true" answer.
        Vector diagonalHingeAxis = new Vector(0.7071067811865476, 0.0, -0.7071067811865476);

        assertEquals(Axis.X, GateBlockOrientation.rotateAxis(Axis.Y, diagonalHingeAxis, 90));
    }

    @Test
    void rotateFace_SwingsNorthToWestAroundAVerticalHinge() {
        // A vertical hinge, like double doors: a door facing NORTH when closed should end up
        // facing WEST once swung open 90 degrees.
        Vector hingeAxis = new Vector(0, 1, 0);

        assertEquals(BlockFace.NORTH, GateBlockOrientation.rotateFace(BlockFace.NORTH, hingeAxis, 0));
        assertEquals(BlockFace.WEST, GateBlockOrientation.rotateFace(BlockFace.NORTH, hingeAxis, 90));
    }

    @Test
    void rotateFace_LeavesNonCardinalFacesUntouched() {
        // BlockFace values outside the 6 cardinal directions (e.g. rail/torch diagonal shapes)
        // aren't in the lookup table - rotateFace must return them unchanged rather than null.
        Vector hingeAxis = new Vector(0, 1, 0);

        assertEquals(BlockFace.NORTH_EAST, GateBlockOrientation.rotateFace(BlockFace.NORTH_EAST, hingeAxis, 90));
    }
}

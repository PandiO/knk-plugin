package net.knightsandkings.knk.core.gates.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.BlockSnapshot;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.gates.GateSpatialIndex;

/** KNG-106: the cells a moving door passes through, in the direction it is moving. */
class GateSweepTest {

    /** A one-block portcullis at (100, 64, 100) that slides 3 blocks up to open, over 60 frames. */
    private static CachedGateDoor portcullis(AnimationState state) {
        CachedGateDoor gate = new CachedGateDoor(1, 1, "Portcullis", "SLIDING", "VERTICAL", "PLANE_GRID",
            60, 1, new Vector(100, 64, 100), 1, 1, 3, 500.0, 500.0, true, false, true, 90, "north");
        gate.setUAxis(new Vector(1, 0, 0));
        gate.setVAxis(new Vector(0, 1, 0));
        gate.setNAxis(new Vector(0, 0, 1));
        gate.setMotionVector(new Vector(0, 3, 0));
        gate.addBlock(new BlockSnapshot(1, new Vector(0, 0, 0), 1, "stone", 0));
        gate.setCurrentState(state);
        return gate;
    }

    /** A drawbridge block 5 blocks from its hinge (z axis at (100, 64, 100)), swinging 90 degrees. */
    private static CachedGateDoor drawbridge(int durationFrames) {
        CachedGateDoor gate = new CachedGateDoor(3, 3, "Drawbridge", "DRAWBRIDGE", "ROTATION", "PLANE_GRID",
            durationFrames, 1, new Vector(100, 64, 100), 0, 0, 0, 500.0, 500.0, true, false, true, 90, "east");
        gate.setUAxis(new Vector(1, 0, 0));
        gate.setVAxis(new Vector(0, 1, 0));
        gate.setNAxis(new Vector(0, 0, 1));
        gate.setHingeAxis(new Vector(0, 0, 1));
        gate.setMotionVector(new Vector(0, 0, 0));
        gate.addBlock(new BlockSnapshot(1, new Vector(5, 0, 0), 1, "stone", 0));
        gate.setCurrentState(AnimationState.OPENING);
        return gate;
    }

    /** KNG-193: a cardinal 2 wide x 3 tall drawbridge with its keyframed plank, hinge along +x at (100,64,100). */
    private static CachedGateDoor keyframedDrawbridge(AnimationState state) {
        CachedGateDoor gate = new CachedGateDoor(4, 4, "Keyframed Drawbridge", "DRAWBRIDGE", "ROTATION", "PLANE_GRID",
            60, 1, new Vector(100, 64, 100), 2, 3, 1, 500.0, 500.0, true, false, true, 90, "south");
        gate.setUStep(new Vector(1, 0, 0));
        gate.setVStep(new Vector(0, 1, 0));
        gate.setNStep(new Vector(0, 0, 1));
        gate.setHingeAxis(new Vector(1, 0, 0));
        gate.setMotionVector(new Vector(0, 0, 0));
        int id = 1;
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 3; j++) {
                gate.addBlock(new BlockSnapshot(id++, new Vector(i, j, 0), 1, "stone", id));
            }
        }
        net.knightsandkings.knk.core.gates.DrawbridgeKeyframes.Result result =
            net.knightsandkings.knk.core.gates.DrawbridgeKeyframes.build(gate, 0, net.knightsandkings.knk.core.gates.DrawbridgeKeyframes.Orienter.IDENTITY);
        gate.setDrawbridgeKeyframes(result.plan());
        gate.setCurrentState(state);
        return gate;
    }

    @Test
    void aKeyframedDrawbridgeSweepsTheUnionOfItsKeyframes() {
        CachedGateDoor gate = keyframedDrawbridge(AnimationState.OPENING);
        var plan = gate.getDrawbridgeKeyframes();
        assertTrue(plan.size() >= 3);

        Set<Long> whole = GateSweep.cells(gate, 0, 60);
        assertEquals(plan.cellsBetween(0, plan.size() - 1), whole);
        assertTrue(has(whole, 100, 66, 100), "the closed top row");
        assertTrue(has(whole, 100, 64, 102), "the open deck's far end");
        assertTrue(has(whole, 101, 65, 101), "the mid-swing diagonal");

        Set<Long> atRest = GateSweep.cells(gate, 0, 0);
        assertEquals(plan.first().packedPositions(), atRest);
        assertFalse(has(atRest, 100, 64, 102));

        // closing: the same union, counted down
        gate.setCurrentState(AnimationState.CLOSING);
        assertEquals(whole, GateSweep.cells(gate, 60, 0));
    }

    private static boolean has(Set<Long> cells, int x, int y, int z) {
        return cells.contains(GateSpatialIndex.packCell(x, y, z));
    }

    @Test
    void closingCountsFramesDownToTheClosedFrame() {
        CachedGateDoor gate = portcullis(AnimationState.CLOSING);

        assertEquals(-1, GateSweep.step(gate));
        assertEquals(25, GateSweep.ahead(gate, 30, 5));
        assertEquals(0, GateSweep.ahead(gate, 3, 5));
        assertEquals(0, GateSweep.endFrame(gate));
    }

    @Test
    void openingCountsFramesUpToTheOpenFrame() {
        CachedGateDoor gate = portcullis(AnimationState.OPENING);

        assertEquals(1, GateSweep.step(gate));
        assertEquals(35, GateSweep.ahead(gate, 30, 5));
        assertEquals(60, GateSweep.ahead(gate, 58, 5));
        assertEquals(60, GateSweep.endFrame(gate));
    }

    @Test
    void aClosingPortcullisSweepsDownIntoTheOpening() {
        CachedGateDoor gate = portcullis(AnimationState.CLOSING);

        // frame 30 is halfway (y 65.5); the rest of the closing passes 65 and lands at 64
        Set<Long> cells = GateSweep.cells(gate, 30, GateSweep.endFrame(gate));

        assertTrue(has(cells, 100, 65, 100));
        assertTrue(has(cells, 100, 64, 100));
        assertFalse(has(cells, 100, 66, 100), "above where the door is now");
    }

    @Test
    void theRotationArcIsCoveredBetweenFrames() {
        // two frames for 90 degrees: 45 degrees per frame, so the sampled positions are far apart
        CachedGateDoor gate = drawbridge(2);

        Set<Long> cells = GateSweep.cells(gate, 0, 2);

        // the block's path: (105, 64) -> (103.5, 67.5) -> (100, 69); at 30 and 60 degrees it is in
        // cells that neither sampled frame occupies
        double r = 5;
        int x30 = (int) Math.floor(100 + r * Math.cos(Math.toRadians(30)));
        int y30 = (int) Math.floor(64 + r * Math.sin(Math.toRadians(30)));
        int x60 = (int) Math.floor(100 + r * Math.cos(Math.toRadians(60)));
        int y60 = (int) Math.floor(64 + r * Math.sin(Math.toRadians(60)));
        assertTrue(has(cells, x30, y30, 100), "30 degrees");
        assertTrue(has(cells, x60, y60, 100), "60 degrees");
        assertTrue(has(cells, 105, 64, 100));
        assertTrue(has(cells, 100, 69, 100));
    }

    @Test
    void everyFrameInTheWindowIsVisitedNotJustTheEnds() {
        CachedGateDoor gate = drawbridge(90);

        Set<Long> cells = GateSweep.cells(gate, 0, 90);

        for (int degrees = 0; degrees <= 90; degrees += 5) {
            int x = (int) Math.floor(100 + 5 * Math.cos(Math.toRadians(degrees)) + 1e-9);
            int y = (int) Math.floor(64 + 5 * Math.sin(Math.toRadians(degrees)) + 1e-9);
            assertTrue(has(cells, x, y, 100), degrees + " degrees");
        }
    }

    @Test
    void aGateWithoutBlocksSweepsNothing() {
        CachedGateDoor gate = new CachedGateDoor(9, 9, "Empty", "SLIDING", "VERTICAL", "PLANE_GRID",
            60, 1, new Vector(0, 64, 0), 1, 1, 1, 500.0, 500.0, true, false, true, 90, "north");

        assertTrue(GateSweep.cells(gate, 0, 60).isEmpty());
        assertTrue(GateSweep.cells(null, 0, 60).isEmpty());
    }
}

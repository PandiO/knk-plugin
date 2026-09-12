package net.knightsandkings.knk.core.gates;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.BlockSnapshot;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for GateManager's spatial-index maintenance in cacheGate().
 */
class GateManagerTest {

    @Test
    void cacheGateIndexesDoorBlocksAtTheGatesCurrentFrame() {
        GateManager manager = new GateManager();

        CachedGateDoor gate = closedGateWithOneBlock(1);
        manager.cacheGate(gate);

        // Block's relative position (0,0,0) at frame 0 world position equals the anchor point.
        Integer resolved = manager.getSpatialIndex().lookup(gate.getWorldName(), 100, 64, 100);
        assertEquals(1, resolved);
    }

    @Test
    void cacheGateIndexesAtTheOpenFrameWhenLoadedAlreadyOpen() {
        GateManager manager = new GateManager();

        CachedGateDoor gate = closedGateWithOneBlock(1);
        gate.setCurrentState(AnimationState.OPEN);
        gate.setCurrentFrame(gate.getAnimationDurationTicks());
        manager.cacheGate(gate);

        // Motion vector (0,3,0) applied at full progress: anchor (100,64,100) -> (100,67,100).
        assertNull(manager.getSpatialIndex().lookup(gate.getWorldName(), 100, 64, 100));
        assertEquals(1, manager.getSpatialIndex().lookup(gate.getWorldName(), 100, 67, 100));
    }

    @Test
    void reCachingAGateClearsItsOldCellsBeforeAddingTheNewOnes() {
        GateManager manager = new GateManager();

        CachedGateDoor original = closedGateWithOneBlock(1);
        manager.cacheGate(original);
        assertEquals(1, manager.getSpatialIndex().lookup(original.getWorldName(), 100, 64, 100));

        // Simulate a reload where the gate's anchor point moved.
        CachedGateDoor reloaded = closedGateWithOneBlockAt(1, new Vector(200, 64, 200));
        manager.cacheGate(reloaded);

        assertNull(manager.getSpatialIndex().lookup(original.getWorldName(), 100, 64, 100));
        assertEquals(1, manager.getSpatialIndex().lookup(reloaded.getWorldName(), 200, 64, 200));
    }

    private static CachedGateDoor closedGateWithOneBlock(int id) {
        return closedGateWithOneBlockAt(id, new Vector(100, 64, 100));
    }

    private static CachedGateDoor closedGateWithOneBlockAt(int id, Vector anchorPoint) {
        CachedGateDoor gate = new CachedGateDoor(
            id,
            1,
            "TestGate",
            "SLIDING",
            "VERTICAL",
            "PLANE_GRID",
            60,
            1,
            anchorPoint,
            5,
            5,
            3,
            500.0,
            500.0,
            true,
            false,
            true,
            90,
            "north"
        );
        gate.setWorldName("world");
        gate.setUAxis(new Vector(1, 0, 0));
        gate.setVAxis(new Vector(0, 1, 0));
        gate.setNAxis(new Vector(0, 0, 1));
        gate.setMotionVector(new Vector(0, 3, 0));
        gate.setCurrentState(AnimationState.CLOSED);
        gate.setCurrentFrame(0);
        gate.addBlock(new BlockSnapshot(1, new Vector(0, 0, 0), 1, "stone", 0));
        return gate;
    }
}

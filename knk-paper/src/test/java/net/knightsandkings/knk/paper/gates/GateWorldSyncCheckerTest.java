package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for the Bukkit-free parts of GateWorldSyncChecker (GATE_WORLD_SYNC_DESIGN.md).
 * check()/fix() themselves need a live World (they read/write real blocks) and are exercised
 * manually per the design doc's Phase 5 plan, matching this repo's existing convention for that
 * class of test (see GateBlockScanTaskHandlerTest, GateRestingFramePlacerTest).
 */
class GateWorldSyncCheckerTest {

    private CachedGateDoor buildGate(AnimationState state) {
        CachedGateDoor gate = new CachedGateDoor(
            1, 1, "Test Gate", "SLIDING", "VERTICAL", "PLANE_GRID",
            60, 1, new Vector(0, 64, 0), 3, 3, 1,
            500.0, 500.0, true, false, true, 90, "north"
        );
        gate.setCurrentState(state);
        return gate;
    }

    @Test
    void restingFrame_ClosedGate_IsFrameZero() {
        assertEquals(0, GateWorldSyncChecker.restingFrame(buildGate(AnimationState.CLOSED)));
    }

    @Test
    void restingFrame_OpenGate_IsTheFinalAnimationFrame() {
        CachedGateDoor gate = buildGate(AnimationState.OPEN);
        assertEquals(gate.getAnimationDurationTicks(), GateWorldSyncChecker.restingFrame(gate));
    }
}

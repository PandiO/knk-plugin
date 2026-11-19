package net.knightsandkings.knk.paper.npc.citizens;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;

/**
 * Link J1 (PandiO/knk-workspace#695): the move control's hop inside a block whose top the body steps onto by itself is
 * told apart from the jumps that are needed.
 */
class StepHopTest {

    private static final double STEP = 0.6;
    /** An upright stair facing north: the lower half over the whole cell, the upper half on the north side. */
    private static final List<BoundingBox> STAIR = List.of(new BoundingBox(0, 0, 0, 1, 0.5, 1),
        new BoundingBox(0, 0.5, 0, 1, 1, 0.5));
    private static final List<BoundingBox> SLAB = List.of(new BoundingBox(0, 0, 0, 1, 0.5, 1));
    private static final List<BoundingBox> FENCE = List.of(new BoundingBox(0.375, 0, 0.375, 0.625, 1.5, 0.625));

    @Test
    void onAStairsLowerHalfTheHopIsNotNeeded() {
        // the developer's trace of 2026-11-17, tick 1496: feet 53.5 in the stair block at y 53, its top at 54
        assertTrue(StepHop.isStepHop(53.5, 53, STAIR, STEP));
    }

    @Test
    void onAStairsUpperHalfThereIsNoHop() {
        assertFalse(StepHop.isStepHop(54.0, 53, STAIR, STEP));
        assertFalse(StepHop.isStepHop(53.9995, 53, STAIR, STEP));
    }

    @Test
    void aBottomSlabAtTheFeetIsAStep() {
        assertTrue(StepHop.isStepHop(53.0, 53, SLAB, STEP));
    }

    @Test
    void aWholeBlockAtTheFeetIsNoStep() {
        // feet at the block's bottom with a stair in it: its top is a whole block up, more than a step
        assertFalse(StepHop.isStepHop(53.0, 53, STAIR, STEP));
    }

    @Test
    void aFenceIsTooHighToStepOnto() {
        assertFalse(StepHop.isStepHop(53.0, 53, FENCE, STEP));
    }

    @Test
    void airGivesNoReason() {
        // a jump with nothing at the feet is one toward a target above: it stays
        assertFalse(StepHop.isStepHop(53.0, 53, List.of(), STEP));
        assertFalse(StepHop.isStepHop(53.0, 53, null, STEP));
    }

    @Test
    void aJumpForATargetAWholeBlockUpWithinABlockIsNeeded() {
        // on a stair's lower half (feet 53.5), a waypoint at 55 (a full block past the stair) 0.8 across: vanilla jumps
        assertTrue(StepHop.jumpsForTarget(0.0, 1.5, -0.8, STEP, 1.0));
        // the stair's upper half (0.5 up) or a waypoint more than a block across: no jump for the target
        assertFalse(StepHop.jumpsForTarget(0.0, 0.5, -0.8, STEP, 1.0));
        assertFalse(StepHop.jumpsForTarget(0.0, 1.5, -1.2, STEP, 1.0));
    }

    @Test
    void besideAStairTheBodyWalksOnTowardAWaypointABlockUp() {
        // the replay's hall entry: feet 115.0, the next cell toward the waypoint at 116 is the stair (its step at 115.5)
        double next = StepHop.nextSurface(115.0, 115.0, 115.5, Double.NaN);
        assertEquals(115.5, next, 1e-9);
        assertTrue(StepHop.walksOn(115.0, next, STEP));
    }

    @Test
    void aFullBlockInTheWayKeepsTheJump() {
        assertFalse(StepHop.walksOn(115.0, StepHop.nextSurface(115.0, 115.0, 116.0, Double.NaN), STEP));
        // a block above the feet's level too: more than a block up
        assertFalse(StepHop.walksOn(115.0, StepHop.nextSurface(115.0, 115.0, 116.0, 117.0), STEP));
    }

    @Test
    void aLevelOrLowerCellIsWalkedOn() {
        assertTrue(StepHop.walksOn(115.0, StepHop.nextSurface(115.0, 115.0, Double.NaN, Double.NaN), STEP));
        assertTrue(StepHop.walksOn(115.0, StepHop.nextSurface(115.0, Double.NaN, Double.NaN, Double.NaN), STEP),
            "no floor: a drop, no jump up");
    }

    @Test
    void aLargerStepHeightStepsHigher() {
        assertTrue(StepHop.isStepHop(53.0, 53, STAIR, 1.0));
    }
}

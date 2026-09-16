package net.knightsandkings.knk.paper.gates;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link GateAnimationTask#resolveVacateCells}, the item 6.8 fix
 * (docs/specs/gate-structure-animation/GATESTRUCTURE_QOL_IMPLEMENTATION_PLAN.md) for
 * orphaned/stuck blocks: deriving what to vacate purely from frame arithmetic
 * (previousFrame = frame +/- tickRate) silently drops every intermediate frame's blocks
 * whenever a main-thread stall skips more than one tickRate step between two
 * {@code updateGateBlocks} calls, since only that single guessed position was ever targeted
 * for removal. {@code resolveVacateCells} is the first, and so far only, pure (Bukkit-free)
 * logic in this otherwise entirely untested {@code BukkitRunnable} class - extracted
 * specifically so this decision is independently verifiable.
 */
class GateAnimationTaskTest {

    @Test
    void resolveVacateCells_NoRememberedState_FallsBackToFrameArithmeticGuess() {
        // First call for a fresh animation (just started, or after a server restart): no memory
        // yet, so the one-time fallback (today's previousFrame-based computation) is used as
        // "what was previously placed" - correct here since a fresh start's first "previous"
        // position genuinely is the resting position.
        GateRestingFramePlacer.RestingCell fallbackCell =
            new GateRestingFramePlacer.RestingCell(new Vector(0, 0, 0), "minecraft:oak_log");
        GateRestingFramePlacer.RestingCell targetCell =
            new GateRestingFramePlacer.RestingCell(new Vector(5, 0, 0), "minecraft:oak_log");

        List<GateRestingFramePlacer.RestingCell> toVacate = GateAnimationTask.resolveVacateCells(
            null, List.of(fallbackCell), List.of(targetCell));

        assertEquals(1, toVacate.size());
        assertEquals(fallbackCell.position(), toVacate.get(0).position());
    }

    @Test
    void resolveVacateCells_RememberedStateExists_ClearsTheRealOrphanedCells_NotJustTheFallbackGuess() {
        // Regression test for the item 6.8 bug itself: a main-thread stall spanning multiple real
        // frames used to leave every skipped frame's blocks permanently orphaned, because only a
        // naive one-frame-back fallback guess was ever vacated. "staleRememberedCell" stands in
        // for several skipped frames' worth of real leftover blocks - far from both the naive
        // fallback guess and this frame's target, exactly the shape of the reported bug. Once
        // remembered state exists, it - not the fallback - must be what actually gets vacated.
        GateRestingFramePlacer.RestingCell staleRememberedCell =
            new GateRestingFramePlacer.RestingCell(new Vector(100, 0, 0), "minecraft:oak_log");
        GateRestingFramePlacer.RestingCell wrongFallbackGuess =
            new GateRestingFramePlacer.RestingCell(new Vector(4, 0, 0), "minecraft:oak_log");
        GateRestingFramePlacer.RestingCell targetCell =
            new GateRestingFramePlacer.RestingCell(new Vector(5, 0, 0), "minecraft:oak_log");

        List<GateRestingFramePlacer.RestingCell> toVacate = GateAnimationTask.resolveVacateCells(
            List.of(staleRememberedCell), List.of(wrongFallbackGuess), List.of(targetCell));

        assertEquals(1, toVacate.size());
        assertEquals(staleRememberedCell.position(), toVacate.get(0).position());
    }

    @Test
    void resolveVacateCells_CellReoccupiedThisFrame_IsNotVacated() {
        // A cell in the previous set that's also part of this frame's target must not be cleared
        // - otherwise a later block's placement would be erased by an earlier one's vacancy, the
        // same guard the old targetCells-based check provided.
        GateRestingFramePlacer.RestingCell reoccupied =
            new GateRestingFramePlacer.RestingCell(new Vector(5, 0, 0), "minecraft:oak_log");
        GateRestingFramePlacer.RestingCell target =
            new GateRestingFramePlacer.RestingCell(new Vector(5, 0, 0), "minecraft:spruce_log");

        List<GateRestingFramePlacer.RestingCell> toVacate = GateAnimationTask.resolveVacateCells(
            List.of(reoccupied), List.of(), List.of(target));

        assertTrue(toVacate.isEmpty());
    }
}

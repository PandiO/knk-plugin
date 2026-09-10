package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.CachedGate;
import net.knightsandkings.knk.core.gates.GateFrameCalculator;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.List;

/**
 * Read-only "does this gate's resting frame match the physical world" check, and the
 * corresponding fix, per docs/features/gate-structure-animation/GATE_WORLD_SYNC_DESIGN.md.
 *
 * <p>Built on {@link GateRestingFramePlacer} (ROTATION_GAP_FILL_DESIGN.md, Phase C) so a
 * rasterized (Mechanism 1) or open-scan-paired (Mechanism 2) gate is checked/fixed the exact
 * same way it's placed - no special-casing needed here.
 */
final class GateWorldSyncChecker {

    private GateWorldSyncChecker() {
    }

    /**
     * @param inSync true iff every checked cell matched (mismatchedCellCount == 0); a gate with
     *               zero checkable cells (nothing loaded) is considered in sync by convention -
     *               callers that care about "was this actually verified at all" should look at
     *               uncheckedCellCount vs. totalCellCount instead.
     */
    record SyncResult(boolean inSync, int mismatchedCellCount, int totalCellCount, int uncheckedCellCount) {

        /** True if not a single cell could be read (e.g. its chunk isn't loaded at all). */
        boolean fullyUnchecked() {
            return totalCellCount > 0 && uncheckedCellCount >= totalCellCount;
        }
    }

    /**
     * Only meaningful for a resting (CLOSED/OPEN) gate - callers are expected to have already
     * filtered out OPENING/CLOSING/destroyed gates (see the three mechanisms in the design doc).
     * A cell whose chunk isn't loaded is skipped (not counted as mismatched) - this method never
     * forces a chunk load; callers decide whether to do that first (Mechanism B) or accept a
     * partial/empty check (Mechanisms A and C, which never force a load).
     */
    static SyncResult check(World world, CachedGate gate, Material fallbackMaterial, boolean rasterizationEnabled) {
        List<GateRestingFramePlacer.RestingCell> cells =
            GateRestingFramePlacer.restingFrameCells(gate, restingFrame(gate), rasterizationEnabled);

        int mismatched = 0;
        int unchecked = 0;
        for (GateRestingFramePlacer.RestingCell cell : cells) {
            Boolean matches = GateBlockPlacer.blockMatches(world, cell.position(), cell.blockData(), fallbackMaterial);
            if (matches == null) {
                unchecked++;
            } else if (!matches) {
                mismatched++;
            }
        }

        return new SyncResult(mismatched == 0, mismatched, cells.size(), unchecked);
    }

    /** Force the gate's resting frame to match its DB-loaded state - see {@link GateRestingFramePlacer}. */
    static void fix(World world, CachedGate gate, Material fallbackMaterial, boolean rasterizationEnabled) {
        int frame = restingFrame(gate);
        double angle = GateFrameCalculator.calculateRotationAngle(gate, frame);
        GateRestingFramePlacer.placeRestingFrame(world, gate, frame, angle, fallbackMaterial, rasterizationEnabled);
    }

    /** The resting animation frame (0 or totalFrames) matching the gate's current CLOSED/OPEN state. */
    static int restingFrame(CachedGate gate) {
        return gate.getCurrentState() == AnimationState.OPEN ? gate.getAnimationDurationTicks() : 0;
    }
}

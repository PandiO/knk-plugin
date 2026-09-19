package net.knightsandkings.knk.core.gates;

import net.knightsandkings.knk.core.domain.gates.BlockSnapshot;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.util.VectorMath;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Calculator for gate animation frame positions.
 * Computes the world position of each gate block based on the current animation frame.
 */
public class GateFrameCalculator {

    private static final double BOUNDS_EPSILON = 0.001;

    /**
     * Calculate the world position of a block at a specific animation frame.
     *
     * @param gate The cached gate containing animation configuration
     * @param block The block snapshot to calculate position for
     * @param frame Current animation frame (0 = closed, animationDurationTicks = open)
     * @return World position for the block at this frame, or null when the block is clipped away
     */
    public static Vector calculateBlockPosition(CachedGateDoor gate, BlockSnapshot block, int frame) {
        return calculateBlockPositionBreakdown(gate, block, frame).finalPosition();
    }

    /**
     * Full breakdown of how {@link #calculateBlockPosition} arrives at a block's position - added
     * during item 6.7's live-testing diagnostic session (2026-09-16) to compare the old, purely
     * procedural rotation against the new Mechanism 2 pairing-blended motion, frame by frame,
     * after the user reported the new motion still "looks weird" despite 6.8/6.9 fixing the
     * orphaned-block and render-cap bugs. Not on the hot per-tick path itself -
     * {@link #calculateBlockPosition} delegates here and returns only {@code finalPosition()},
     * behaviorally unchanged - this exists purely so a caller that wants to log/inspect the
     * intermediate values (see {@code GateAnimationTask}'s per-frame trace logging) doesn't have
     * to duplicate this method's math to get them.
     *
     * @param baselinePosition What this block's position would be with zero Mechanism 2 influence
     *     - the pure rotation arc (ROTATION motion) or the plain procedural position (other motion
     *     types with no pairing) - i.e. "what the old system, before item 6, would have placed
     *     this block at." For a VERTICAL/LATERAL block that IS paired, this is its closed-anchor
     *     position (the lerp's start point), for consistency with the ROTATION case.
     * @param pairedOpenBlockId The paired open-scan {@code BlockSnapshot}'s id, or null if this
     *     block has no Mechanism 2 pairing.
     * @param openTarget The paired open-scan block's real world position, or null if unpaired.
     * @param correction {@code finalPosition - baselinePosition} (pre-clip) - the total blend term
     *     (uniform transform correction plus, for a {@code ROTATION} block with a pairing, item
     *     6.11's residual - see {@code residual} below), or null if unpaired. Its magnitude at
     *     progress=1 is exactly how far this block's true open-scan position sits from where pure
     *     procedural motion would have put it - the number to watch to find which blocks are
     *     driving non-rigid-looking motion.
     * @param finalPosition The same value {@link #calculateBlockPosition} returns - null if
     *     clipped outside the gate's geometry bounds.
     * @param residual Item 6.11 (Decision 8, ROTATION_GAP_FILL_DESIGN.md): for a {@code ROTATION}
     *     block with both a fitted transform and a pairing, the tapered per-block correction on
     *     top of the shared/uniform one - this block's own gap between its real scanned position
     *     and the shared transform's prediction for it, closing exactly at progress=1. Null when
     *     not applicable (no fit, no pairing, or a non-{@code ROTATION} motion type). Always
     *     (numerically) zero for an open-only block, by construction - included anyway so the
     *     trace log can show it's behaving as expected rather than omitting it.
     */
    public record BlockPositionBreakdown(Vector baselinePosition, Integer pairedOpenBlockId, Vector openTarget,
                                          Vector correction, Vector finalPosition, Vector residual) {
    }

    public static BlockPositionBreakdown calculateBlockPositionBreakdown(CachedGateDoor gate, BlockSnapshot block, int frame) {
        if (gate == null || block == null) {
            throw new IllegalArgumentException("Gate and block cannot be null");
        }

        // Clamp frame to valid range
        int totalFrames = gate.getAnimationDurationTicks();
        frame = Math.max(0, Math.min(frame, totalFrames));

        // Calculate normalized progress (0.0 = closed, 1.0 = open)
        double progress = totalFrames > 0 ? (double) frame / totalFrames : 0.0;

        // Get block's relative position in gate's local coordinate system
        Vector relativePos = block.getRelativePosition();

        // Calculate world position based on motion type
        String motionType = gate.getMotionType();

        // Mechanism 2 (ROTATION_GAP_FILL_DESIGN.md): a block paired with a manually-scanned open
        // state is steered toward that real position instead of (or, for ROTATION, blended with)
        // the purely procedural one. Absent for the vast majority of gates (empty pairing), in
        // which case every branch below reduces to today's exact original formulas.
        BlockSnapshot pairedOpen = gate.getPairedOpenBlock(block.getId());
        Vector openTarget = (pairedOpen != null && gate.getOpenAnchorPoint() != null)
            ? gate.getOpenAnchorPoint().clone().add(pairedOpen.getRelativePosition())
            : null;

        Vector baselinePosition;
        Vector correction = null;
        Vector residual = null;
        Vector position;
        if ("ROTATION".equals(motionType)) {
            RigidTransform transform = gate.getFittedOpenTransform();
            RotationBlend blend = blendRotationPosition(gate, relativePos, progress, transform, openTarget);
            baselinePosition = blend.arcPos();
            correction = blend.correction();
            residual = blend.residual();
            position = blend.position();
        } else if (openTarget != null) {
            // VERTICAL/LATERAL: the real motion already is a straight line, so a plain lerp
            // between the closed and open-scan positions is correct on its own (DUAL_SCAN_
            // ANIMATION_DESIGN.md's original design for this case).
            Vector closedPos = gate.getAnchorPoint().clone().add(relativePos);
            baselinePosition = closedPos;
            position = VectorMath.lerp(closedPos, openTarget, progress);
            correction = position.clone().subtract(baselinePosition);
        } else {
            position = calculateLinearPosition(gate, relativePos, progress);
            baselinePosition = position;
        }

        Vector finalPosition = (gate.isClipToGeometryBounds() && !isWithinGeometryBounds(gate, position)) ? null : position;

        return new BlockPositionBreakdown(baselinePosition, pairedOpen != null ? pairedOpen.getId() : null,
            openTarget, correction, finalPosition, residual);
    }

    /**
     * The result of blending a {@code ROTATION} block's rigid rotation arc with the fitted
     * transform's uniform correction and (item 6.11) a per-block residual - shared by {@link
     * #calculateBlockPositionBreakdown} (a real paired/unpaired closed block) and {@link
     * #calculateOpenOnlyBlockPositionBreakdown} (an open-only block's synthesized start point), so
     * both go through the exact same formula rather than two copies that could drift apart.
     */
    private record RotationBlend(Vector arcPos, Vector correction, Vector residual, Vector position) {
    }

    /**
     * Item 6.10 (Decision 7) + item 6.11 (Decision 8), both in ROTATION_GAP_FILL_DESIGN.md:
     * blends a {@code ROTATION} block's pure rigid rotation arc with the fitted transform's shared,
     * uniform correction, plus - when {@code openTarget} is non-null (a real pairing) - a small
     * per-block residual that closes this specific block's own gap between the shared transform's
     * prediction and its true scanned position, tapered in non-linearly so it only becomes
     * significant near the end of the swing.
     *
     * <p><strong>Why the residual's taper must not be plain {@code progress}</strong> (Decision 8):
     * the uniform correction is already {@code (transformPos - arcPosFinal) * progress}. If the
     * residual - {@code (openTarget - transformPos)} - were ALSO tapered by plain {@code progress},
     * the two {@code transformPos} terms cancel algebraically:
     * {@code (transformPos - arcPosFinal)*progress + (openTarget - transformPos)*progress =
     * (openTarget - arcPosFinal) * progress} - exactly the old, pre-6.10 per-block blend this whole
     * item exists to move away from. The residual's taper must be a genuinely different curve (see
     * {@link #residualTaper}) so the two corrections don't silently recombine into one.
     *
     * <p>Endpoints stay exact regardless of the taper's exact shape: at {@code progress=0} both
     * corrections are zero (the taper and the uniform factor both vanish), giving exactly the
     * closed position; at {@code progress=1} the uniform correction reaches {@code transformPos -
     * arcPosFinal} and the residual reaches its full {@code openTarget - transformPos} (any taper
     * with {@code f(1)=1} gives this), so the two combine to {@code openTarget - arcPosFinal}
     * exactly - {@code position(1) = arcPosFinal + (openTarget - arcPosFinal) = openTarget}, for
     * every block with a pairing, paired-closed or open-only alike.
     *
     * <p>For an open-only block (called from {@link #calculateOpenOnlyBlockPositionBreakdown} with
     * {@code openTarget} = that block's own real open-scan position and {@code relativePos} = its
     * synthesized closed-frame start), the residual is always numerically zero: {@code
     * transformPos} there is {@code transform.apply(anchor + synthesizedRelativePos)}, and
     * {@code synthesizedRelativePos} is defined as the transform's own inverse applied to {@code
     * openTarget} - so {@code transformPos} reduces to {@code openTarget} exactly, making {@code
     * openTarget - transformPos} the zero vector regardless of the taper. No special-casing needed.
     *
     * @param transform The gate's fitted rigid transform, or null (Decision 7's degenerate
     *     fallback) - the whole blend then degrades to the pure rotation arc.
     * @param openTarget The real scanned position this block should converge onto by
     *     {@code progress=1} - a paired closed block's own paired open-scan position, an open-only
     *     block's own real position, or null (no pairing - no residual, uniform correction only).
     */
    private static RotationBlend blendRotationPosition(CachedGateDoor gate, Vector relativePos, double progress,
                                                         RigidTransform transform, Vector openTarget) {
        Vector arcPos = calculateRotationPosition(gate, relativePos, progress);
        if (transform == null) {
            return new RotationBlend(arcPos, null, null, arcPos);
        }

        Vector arcPosFinal = calculateRotationPosition(gate, relativePos, 1.0);
        Vector closedWorldPos = gate.getAnchorPoint().clone().add(relativePos);
        Vector transformPos = transform.apply(closedWorldPos);
        Vector uniformCorrection = transformPos.clone().subtract(arcPosFinal).multiply(progress);

        Vector residual = null;
        Vector totalCorrection = uniformCorrection;
        Vector position;
        if (openTarget != null) {
            double taper = residualTaper(progress);
            residual = openTarget.clone().subtract(transformPos).multiply(taper);
            totalCorrection = uniformCorrection.clone().add(residual);
            Vector blended = arcPos.clone().add(totalCorrection);

            // Item 6.11.2 (Decision 8, second follow-up, ROTATION_GAP_FILL_DESIGN.md): a FIXED
            // progress threshold (item 6.11.1's first attempt) doesn't work, because the frame at
            // which a block gets dangerously close to its real target isn't a fixed fraction of the
            // swing - it depends on that specific block's own residual size and the neighboring
            // blocks it might collide with, which live testing showed varies (frame 89 for one
            // pair, frame 84 for a different pair, on the SAME door). Distance-based snapping
            // instead: once the blend has naturally converged to within SNAP_DISTANCE of the real,
            // by-definition-distinct target - close enough that Math.floor() (what actually decides
            // a block's placed world cell) cannot possibly mistake it for any OTHER real target
            // (every scanned block is at least 1 full block from its nearest neighbor on the
            // integer lattice) - snap to it exactly, whichever frame that happens to occur on. This
            // is what actually adapts to each block's own convergence rate instead of guessing a
            // global timing constant that only fit the last failure observed.
            if (blended.distanceSquared(openTarget) < SNAP_DISTANCE * SNAP_DISTANCE) {
                position = openTarget.clone();
            } else {
                position = blended;
            }
        } else {
            position = arcPos.clone().add(totalCorrection);
        }

        return new RotationBlend(arcPos, totalCorrection, residual, position);
    }

    /**
     * Item 6.11.2's snap radius (Decision 8, second follow-up) - once a block with a real target
     * has naturally converged (via {@link #blendRotationPosition}'s arc+uniform+residual blend) to
     * within this many blocks of it, the position snaps to the target exactly instead of continuing
     * to approach asymptotically. 0.5 is the largest radius that's still unconditionally safe: two
     * distinct real scanned blocks are never less than 1 full block apart (they're on the integer
     * lattice), so being within 0.5 of one target rules out ever being simultaneously within 0.5 of
     * a different one - {@code Math.floor()} (what actually decides a block's placed world cell)
     * can no longer be ambiguous about which real block this is. A smaller radius would still be
     * correct but would shrink the window in which this protection applies for no benefit; a larger
     * one would start risking exactly the ambiguity this exists to prevent.
     */
    private static final double SNAP_DISTANCE = 0.5;

    /**
     * Item 6.11's per-block residual taper (Decision 8, ROTATION_GAP_FILL_DESIGN.md) - deliberately
     * NOT plain {@code progress} (see {@link #blendRotationPosition}'s javadoc for the algebraic
     * cancellation that would cause). An ease-in curve: {@code f(0)=0, f(1)=1} (so endpoints stay
     * exact regardless of the exponent - a correctness-independent tuning choice, not a
     * correctness requirement, since {@link #SNAP_DISTANCE}'s snap is what actually guarantees no
     * collision, not this taper). Raised from cubic to quintic (2026-09-19, live-testing feedback
     * that the swing still wasn't rigid-looking enough): a well-fit block's residual contribution
     * now stays under ~3% through the first half of the swing (was ~13% at cubic) and under ~10%
     * through 70% of the swing (was ~34%) - a genuinely non-rigid outlier's individual catch-up
     * motion is pushed even later and becomes more concentrated, while {@link #SNAP_DISTANCE}
     * still independently guarantees exact, collision-free convergence regardless of how late that
     * catch-up starts. Revisit this constant (not the shape of the formula, and not the snap
     * mechanism, which is a separate and already-proven-safe guarantee) if live testing shows the
     * catch-up motion is now too abrupt (a visible last-instant snap) rather than too early.
     */
    private static double residualTaper(double progress) {
        double squared = progress * progress;
        return squared * squared * progress;
    }

    /** @see #calculateOpenOnlyBlockPositionBreakdown */
    public static Vector calculateOpenOnlyBlockPosition(CachedGateDoor gate, BlockSnapshot openBlock, int frame) {
        return calculateOpenOnlyBlockPositionBreakdown(gate, openBlock, frame).finalPosition();
    }

    /**
     * Mid-swing position for a block that exists only in the open scan (no closed-state
     * counterpart) - item 6.10's fix for the "open-only blocks pop in at the last tick" trade-off
     * item 6.9 explicitly accepted. Only possible when the gate has a fitted rigid transform (see
     * {@link CachedGateDoor#getFittedOpenTransform()}): {@code openBlock}'s real open-scan world
     * position is inverse-transformed back through that same shared transform (once, at load time
     * - see {@code GateLoaderAdapter}) to synthesize a closed-side start point, then run through
     * the exact same arc-plus-correction blend {@link #calculateBlockPositionBreakdown}'s
     * {@code ROTATION} branch uses for a real paired block. Because the synthesized start point is
     * defined as the transform's own inverse, {@code transform.apply(closedWorldPos)} reduces
     * exactly to this block's real open-scan position by construction - so this still converges
     * exactly onto the real scanned position at {@code progress=1}, same guarantee as a paired
     * block gets.
     *
     * @return a breakdown with a null {@code finalPosition} (nothing to place this frame) when no
     *     fit is available, or {@code openBlock} isn't a recognized open-only block for this gate
     *     (e.g. it's actually paired, or this isn't a ROTATION gate) - callers should simply skip
     *     placing it, exactly like today's unpaired-block behavior.
     */
    public static BlockPositionBreakdown calculateOpenOnlyBlockPositionBreakdown(CachedGateDoor gate, BlockSnapshot openBlock, int frame) {
        if (gate == null || openBlock == null) {
            throw new IllegalArgumentException("Gate and block cannot be null");
        }

        RigidTransform transform = gate.getFittedOpenTransform();
        Vector synthesizedRelativePos = gate.getOpenOnlyBlockSynthesizedRelativePosition(openBlock.getId());
        if (transform == null || synthesizedRelativePos == null || !"ROTATION".equals(gate.getMotionType())) {
            return new BlockPositionBreakdown(null, openBlock.getId(), null, null, null, null);
        }

        int totalFrames = gate.getAnimationDurationTicks();
        frame = Math.max(0, Math.min(frame, totalFrames));
        double progress = totalFrames > 0 ? (double) frame / totalFrames : 0.0;

        Vector openWorldPos = gate.getOpenAnchorPoint().clone().add(openBlock.getRelativePosition());
        RotationBlend blend = blendRotationPosition(gate, synthesizedRelativePos, progress, transform, openWorldPos);

        Vector finalPosition = (gate.isClipToGeometryBounds() && !isWithinGeometryBounds(gate, blend.position())) ? null : blend.position();

        return new BlockPositionBreakdown(blend.arcPos(), openBlock.getId(), openWorldPos, blend.correction(), finalPosition, blend.residual());
    }

    /**
     * Projects a world position back onto the gate's u/v/n basis and checks it against the
     * Width/Height/Depth box. Lets a door retract into a housing instead of sticking out of it.
     */
    public static boolean isWithinGeometryBounds(CachedGateDoor gate, Vector worldPosition) {
        if (gate == null || worldPosition == null) {
            return false;
        }

        Vector anchor = gate.getAnchorPoint();
        if ("VERTICAL".equals(gate.getMotionType())) {
            return isWithinVerticalOpening(gate, anchor, worldPosition);
        }

        Vector uStep = gate.getUStep();
        Vector vStep = gate.getVStep();
        Vector nStep = gate.getNStep();

        if (anchor == null || uStep == null || vStep == null || nStep == null
            || uStep.lengthSquared() == 0 || vStep.lengthSquared() == 0 || nStep.lengthSquared() == 0) {
            return true;
        }

        Vector local = worldPosition.clone().subtract(anchor);
        double[] indices = projectOntoBasis(local, uStep, vStep, nStep);

        if ("REGION".equals(gate.getGeometryDefinitionMode())) {
            List<double[]> footprint = gate.getClosedFootprintUV();
            // No captured footprint yet (not scanned, or a parse failure at load) - fail open,
            // matching the box case's own "missing basis" fail-open two lines above, rather than
            // clipping every block away.
            return footprint == null || footprint.isEmpty()
                || (withinAxis(indices[2], gate.getGeometryDepth()) && pointInPolygon(indices[0], indices[1], footprint));
        }

        return withinAxis(indices[0], gate.getGeometryWidth())
            && withinAxis(indices[1], gate.getGeometryHeight())
            && withinAxis(indices[2], gate.getGeometryDepth());
    }

    /**
     * Projects a local (anchor-relative) offset onto the (possibly non-unit-length, e.g.
     * diagonal) uStep/vStep/nStep basis using the standard oblique-basis formula
     * local.dot(step)/|step|^2, which recovers the true integer width/height/depth index -
     * unlike a unit-vector dot product, this stays exact for a diagonal step like (1,0,1) whose
     * length is sqrt(2), not 1. Shared by {@link #isWithinGeometryBounds} (bounds check against
     * the gate's own, unrotated box) and {@link #rasterizeRotationFrame} (material lookup against
     * the same box, after inverse-rotating a rasterized candidate back into it).
     *
     * <p>Public (not just package-private) so {@code GateLoaderAdapter} (a different module,
     * {@code knk-paper}) can reuse it to project a REGION-mode door's captured world-space
     * footprint vertices into the same u/v index space at load time - see
     * WORLDGUARD_REGION_FEASIBILITY.md §9.3.
     *
     * @return {u, v, n} continuous (unrounded) indices
     */
    public static double[] projectOntoBasis(Vector local, Vector uStep, Vector vStep, Vector nStep) {
        double uIndex = uStep.lengthSquared() > 0 ? local.dot(uStep) / uStep.lengthSquared() : 0;
        double vIndex = vStep.lengthSquared() > 0 ? local.dot(vStep) / vStep.lengthSquared() : 0;
        double nIndex = nStep.lengthSquared() > 0 ? local.dot(nStep) / nStep.lengthSquared() : 0;
        return new double[]{uIndex, vIndex, nIndex};
    }

    private static boolean withinAxis(double projection, int extent) {
        if (extent <= 0) {
            return true;
        }
        return projection >= -BOUNDS_EPSILON && projection <= extent - 1 + BOUNDS_EPSILON;
    }

    /**
     * REGION mode's containment predicate (WORLDGUARD_REGION_FEASIBILITY.md §9.3): the polygon
     * analogue of {@link #withinAxis}'s rectangle box test, used by {@link
     * #isWithinGeometryBounds} and {@link #rasterizeRotationFrame} in place of the width/height
     * bounds check. Standard even-odd ray-casting in u/v index space - a boundary edge counts as
     * inside (via {@link #BOUNDS_EPSILON}) so a point that lands exactly on a captured region's
     * edge (common for the closed/starting frame, since that's exactly what got scanned) isn't
     * spuriously excluded by floating-point noise.
     *
     * <p>No Bukkit dependency, pure function of already-projected u/v coordinates - directly
     * unit-testable like every other method in this file.
     *
     * @param polygonUV the footprint's vertices in u/v index space, in order (not necessarily
     *                  closed - the last point connects back to the first)
     */
    static boolean pointInPolygon(double u, double v, List<double[]> polygonUV) {
        if (polygonUV == null || polygonUV.size() < 3) {
            return false;
        }

        boolean inside = false;
        int n = polygonUV.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double ui = polygonUV.get(i)[0];
            double vi = polygonUV.get(i)[1];
            double uj = polygonUV.get(j)[0];
            double vj = polygonUV.get(j)[1];

            // On-edge check first (inclusive boundary, within epsilon) - a plain ray-cast alone
            // would leave this to floating-point luck.
            if (isOnSegment(u, v, ui, vi, uj, vj)) {
                return true;
            }

            boolean straddles = (vi > v) != (vj > v);
            if (straddles) {
                double uCrossing = ui + (v - vi) / (vj - vi) * (uj - ui);
                if (u < uCrossing) {
                    inside = !inside;
                }
            }
        }
        return inside;
    }

    private static boolean isOnSegment(double u, double v, double ui, double vi, double uj, double vj) {
        double crossProduct = (v - vi) * (uj - ui) - (u - ui) * (vj - vi);
        if (Math.abs(crossProduct) > BOUNDS_EPSILON * Math.max(1.0, Math.hypot(uj - ui, vj - vi))) {
            return false;
        }
        double dotProduct = (u - ui) * (uj - ui) + (v - vi) * (vj - vi);
        if (dotProduct < -BOUNDS_EPSILON) {
            return false;
        }
        double squaredLength = (uj - ui) * (uj - ui) + (vj - vi) * (vj - vi);
        return dotProduct <= squaredLength + BOUNDS_EPSILON;
    }

    /**
     * 2D convex hull (Andrew's monotone chain), returned counterclockwise. Needed specifically
     * for a captured {@code CONVEX_POLYHEDRON} footprint (WORLDGUARD_REGION_FEASIBILITY.md §9's
     * follow-up on non-horizontal shapes): WorldEdit stores that region's vertices as an
     * unordered {@code Set}, but {@link #pointInPolygon}'s ray-casting requires points traced
     * around the boundary in order. A convex 3D shape's projection onto any 2D plane is itself
     * convex, so re-deriving the hull after projecting into u/v space recovers a valid boundary
     * trace regardless of what order the vertices arrived in.
     *
     * <p>Callers must only apply this to a footprint that's known to originate from a genuinely
     * convex source (see {@code GateRegionDataFormat#isConvexPolyhedron}) - running it on a
     * {@code POLYGON2D} capture would silently "fill in" any legitimately concave notch (e.g. an
     * L-shaped outline) into its convex bounding shape instead.
     *
     * <p>Public (not just package-private) so {@code GateLoaderAdapter} (a different module,
     * {@code knk-paper}) can call it - the same reason {@link #projectOntoBasis} is public.
     *
     * @return the hull's vertices in order; a degenerate input (fewer than 3 distinct points, or
     *         all collinear) returns as many of the (deduplicated, sorted) input points as remain
     */
    public static List<double[]> convexHull2D(List<double[]> pointsUV) {
        if (pointsUV == null || pointsUV.size() < 2) {
            return pointsUV == null ? List.of() : new ArrayList<>(pointsUV);
        }

        List<double[]> sorted = new ArrayList<>(pointsUV);
        sorted.sort((a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]));

        // Deduplicate consecutive equal points (harmless for the hull, avoids degenerate
        // zero-length segments confusing the cross-product turn test below).
        List<double[]> unique = new ArrayList<>();
        for (double[] p : sorted) {
            if (unique.isEmpty() || p[0] != unique.get(unique.size() - 1)[0] || p[1] != unique.get(unique.size() - 1)[1]) {
                unique.add(p);
            }
        }
        if (unique.size() < 3) {
            return unique;
        }

        List<double[]> lower = new ArrayList<>();
        for (double[] p : unique) {
            while (lower.size() >= 2 && cross(lower.get(lower.size() - 2), lower.get(lower.size() - 1), p) <= 0) {
                lower.remove(lower.size() - 1);
            }
            lower.add(p);
        }

        List<double[]> upper = new ArrayList<>();
        for (int i = unique.size() - 1; i >= 0; i--) {
            double[] p = unique.get(i);
            while (upper.size() >= 2 && cross(upper.get(upper.size() - 2), upper.get(upper.size() - 1), p) <= 0) {
                upper.remove(upper.size() - 1);
            }
            upper.add(p);
        }

        lower.remove(lower.size() - 1);
        upper.remove(upper.size() - 1);
        lower.addAll(upper);
        return lower;
    }

    private static double cross(double[] o, double[] a, double[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }

    private static boolean isWithinVerticalOpening(CachedGateDoor gate, Vector anchor, Vector worldPosition) {
        if (anchor == null || gate.getGeometryHeight() <= 0) {
            return true;
        }

        int minY = anchor.getBlockY();
        int maxY = minY + gate.getGeometryHeight() - 1;
        int y = worldPosition.getBlockY();

        return y >= minY && y <= maxY;
    }

    /**
     * Calculate position for linear motion (VERTICAL or LATERAL).
     * Formula: worldPos = anchorPoint + relativePos + (motionVector * progress)
     * 
     * @param gate The cached gate
     * @param relativePos Block's relative position
     * @param progress Animation progress (0.0 to 1.0)
     * @return World position
     */
    private static Vector calculateLinearPosition(CachedGateDoor gate, Vector relativePos, double progress) {
        Vector anchorPoint = gate.getAnchorPoint();
        Vector motionVector = gate.getMotionVector();

        if (anchorPoint == null || relativePos == null || motionVector == null) {
            return new Vector(0, 0, 0);
        }

        // Start position: anchor + relative offset
        Vector closedPos = anchorPoint.clone().add(relativePos);

        // Apply motion: move along motion vector
        Vector displacement = motionVector.clone().multiply(progress);

        return closedPos.add(displacement);
    }

    /**
     * Calculate position for rotation motion (DRAWBRIDGE, DOUBLE_DOORS).
     * Formula: worldPos = anchorPoint + rotateAroundAxis(relativePos, hingeAxis, currentAngle)
     * 
     * @param gate The cached gate
     * @param relativePos Block's relative position
     * @param progress Animation progress (0.0 to 1.0)
     * @return World position
     */
    private static Vector calculateRotationPosition(CachedGateDoor gate, Vector relativePos, double progress) {
        Vector anchorPoint = gate.getAnchorPoint();
        Vector hingeAxis = gate.getHingeAxis();

        if (anchorPoint == null || relativePos == null || hingeAxis == null) {
            return anchorPoint != null ? anchorPoint.clone().add(relativePos) : new Vector(0, 0, 0);
        }

        double currentAngle = gate.getRotationMaxAngleDegrees() * progress;

        // Rotate the relative position around the hinge axis
        Vector rotatedPos = VectorMath.rotateAroundAxis(relativePos, hingeAxis, currentAngle);

        // Add to anchor point to get world position
        return anchorPoint.clone().add(rotatedPos);
    }

    /**
     * The rotation angle (degrees) a ROTATION-motion gate has swung through at a given frame -
     * the same angle {@link #calculateRotationPosition} applies to each block's position. Exposed
     * so a block's own facing/axis orientation can be kept in sync with how far the door has
     * physically swung open, instead of staying frozen at its closed (scanned) orientation.
     * Returns 0 for non-ROTATION gates, where no reorientation should ever be applied.
     *
     * @param gate The cached gate
     * @param frame Animation frame to evaluate
     * @return Rotation angle in degrees (0 at frame 0, RotationMaxAngleDegrees at the last frame)
     */
    public static double calculateRotationAngle(CachedGateDoor gate, int frame) {
        if (gate == null || !"ROTATION".equals(gate.getMotionType())) {
            return 0.0;
        }

        int totalFrames = gate.getAnimationDurationTicks();
        frame = Math.max(0, Math.min(frame, totalFrames));
        double progress = totalFrames > 0 ? (double) frame / totalFrames : 0.0;

        return gate.getRotationMaxAngleDegrees() * progress;
    }

    /**
     * One rasterized (i.e. gap-filled, not individually scanned) block: a real-grid world
     * position discovered by {@link #rasterizeRotationFrame}, together with the closed-state
     * scanned block whose material/blockdata it should be placed with.
     */
    public record RasterizedBlock(Vector worldPosition, BlockSnapshot sourceBlock) {
    }

    /**
     * Mechanism 1 (automatic rasterized gap-fill) from ROTATION_GAP_FILL_DESIGN.md: rotates the
     * door's 4 local-space corners by angleDegrees around the gate's hinge axis, takes the
     * axis-aligned bounding box of the result, and tests every integer world cell in that box by
     * inverse-rotating it back into the closed (unrotated) frame and reusing {@link
     * #projectOntoBasis}'s exact oblique-basis projection - the same check {@link
     * #isWithinGeometryBounds} performs, just against a candidate that's been un-rotated first
     * instead of a step basis that's been rotated forward (equivalent, and lets this reuse the
     * gate's own stored, unrotated uStep/vStep/nStep unchanged). A cell that passes is assigned
     * the material/blockdata of whichever originally-scanned block shares its (rounded) u/v index.
     *
     * <p>Pure function of the gate's own geometry - no Bukkit World/Block access - so it's
     * directly unit-testable. Intended to run only at the two resting (closed/open) endpoint
     * frames, never mid-swing (see Decision 3: matching every tick's cost of the current sparse
     * sweep) - callers are responsible for that gating; this method itself is frame-count-agnostic
     * and just rasterizes whatever single angle it's given.
     *
     * @param gate The cached gate (must be a ROTATION gate with a valid hinge axis and lattice steps)
     * @param angleDegrees The rotation angle (degrees) to rasterize at - 0 for closed, RotationMaxAngleDegrees for open
     * @return every real-grid cell inside the rotated footprint, with its sourced material; empty if the gate lacks the geometry to rasterize
     */
    public static List<RasterizedBlock> rasterizeRotationFrame(CachedGateDoor gate, double angleDegrees) {
        List<RasterizedBlock> result = new ArrayList<>();
        if (gate == null) {
            return result;
        }

        Vector anchor = gate.getAnchorPoint();
        Vector hingeAxis = gate.getHingeAxis();
        Vector uStep = gate.getUStep();
        Vector vStep = gate.getVStep();
        Vector nStep = gate.getNStep();
        int width = Math.max(1, gate.getGeometryWidth());
        int height = Math.max(1, gate.getGeometryHeight());

        if (anchor == null || hingeAxis == null || uStep == null || vStep == null || nStep == null
            || gate.getBlocks().isEmpty()) {
            return result;
        }

        // 1. Rotate the footprint's local corners (in u/v index space) by angleDegrees around the
        // hinge - the door's own captured polygon for REGION mode (§9.3), or the 4 rectangle
        // corners for PLANE_GRID/FLOOD_FILL (unchanged from before REGION mode existed).
        boolean isRegionMode = "REGION".equals(gate.getGeometryDefinitionMode());
        List<double[]> footprintUV = isRegionMode ? gate.getClosedFootprintUV() : null;
        if (isRegionMode && (footprintUV == null || footprintUV.isEmpty())) {
            return result; // no captured footprint yet - nothing to rasterize
        }

        List<double[]> cornerIndices = isRegionMode ? footprintUV
            : List.of(new double[]{0, 0}, new double[]{width - 1, 0}, new double[]{0, height - 1}, new double[]{width - 1, height - 1});
        Vector[] rotatedCorners = new Vector[cornerIndices.size()];
        for (int c = 0; c < cornerIndices.size(); c++) {
            double[] corner = cornerIndices.get(c);
            Vector localCorner = uStep.clone().multiply(corner[0])
                .add(vStep.clone().multiply(corner[1]));
            rotatedCorners[c] = anchor.clone().add(VectorMath.rotateAroundAxis(localCorner, hingeAxis, angleDegrees));
        }

        // 2. Axis-aligned bounding box of the rotated corners, rounded outward to integer cells.
        // No epsilon slop here (unlike the projection check below): these are exact rotations of
        // integer inputs by the exact configured angle, and padding the box would let stray
        // integer cells outside the true footprint spuriously pass step 4's projection test,
        // which only constrains u/v/n index *ratios* - not distance from the rotated rectangle -
        // so it cannot by itself reject a cell the AABB should never have offered it in the first place.
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Vector corner : rotatedCorners) {
            minX = Math.min(minX, (int) Math.floor(corner.getX()));
            minY = Math.min(minY, (int) Math.floor(corner.getY()));
            minZ = Math.min(minZ, (int) Math.floor(corner.getZ()));
            maxX = Math.max(maxX, (int) Math.ceil(corner.getX()));
            maxY = Math.max(maxY, (int) Math.ceil(corner.getY()));
            maxZ = Math.max(maxZ, (int) Math.ceil(corner.getZ()));
        }

        // 3. Index every originally-scanned block by its own (rounded) u/v index, for step 4's
        // material lookup.
        Map<Long, BlockSnapshot> byIndex = new HashMap<>();
        for (BlockSnapshot block : gate.getBlocks()) {
            double[] idx = projectOntoBasis(block.getRelativePosition(), uStep, vStep, nStep);
            byIndex.putIfAbsent(packIndex(Math.round(idx[0]), Math.round(idx[1])), block);
        }

        // 3b. Guarantee every scanned source block contributes at least one placed cell, by
        // rotating it forward directly (the same formula calculateRotationPosition uses for the
        // non-rasterized path) and claiming the nearest still-free integer cell to that continuous
        // position. Step 4 below only *tests candidate world cells against the closed frame*: for
        // a diagonal hinge, a genuine source cell's true rotated position frequently doesn't
        // reverse-project back to an exact integer (u,v) for *any* nearby integer cell (the
        // rotation isn't lattice-preserving off cardinal axes), so step 4 alone silently drops
        // whole rows/columns near the geometry's edges - this is what produced the reported
        // "8-tall door only opens 6 blocks" bug: an entire column's blocks are, geometrically,
        // spaced *less than one block apart* once projected onto world X/Z after a diagonal-axis
        // rotation (e.g. 1/sqrt(2) per row for a 45-degree hinge), so several adjacent rows are
        // mathematically guaranteed to round to the very same integer cell - this is an intrinsic
        // consequence of representing a continuously-rotated diagonal surface with unit blocks,
        // not something any lookup strategy can avoid entirely.
        //
        // What *is* avoidable is which row wins that collision, and what happens to the loser:
        // - Processing farthest-from-hinge rows first (descending v-index) means the door's true,
        //   farthest reach always survives a collision, rather than being clobbered by whichever
        //   row happened to be processed first.
        // - The loser of a collision isn't simply dropped: claimNearestAvailableCell tries all 8
        //   floor/ceil corners around its true continuous position (not just floor), so it can
        //   still claim a distinct, merely-adjacent cell instead of vanishing outright. This also
        //   subsumes the floating-point case where a coordinate that's algebraically supposed to
        //   land exactly on an integer (e.g. the hinge row) comes out a few ULPs to either side of
        //   it instead (Math.cos(Math.PI / 2) is ~6.12e-17, not bit-exact 0) - the correct corner
        //   is simply whichever of the 8 is nearest, no separate epsilon needed.
        // - Relying only on step 4's independent box-scan to patch up collision losers (as an
        //   earlier version of this fix did) left a systematic gap at the geometry's edge columns,
        //   which have fewer neighboring candidate cells for step 4 to find a match through than
        //   interior columns do - reproducing a milder version of the same "one column worse than
        //   the rest" bug. Recovering within 3b itself removes that dependency entirely.
        List<BlockSnapshot> byDistanceFromHinge = new ArrayList<>(gate.getBlocks());
        byDistanceFromHinge.sort((a, b) -> {
            double vA = Math.abs(projectOntoBasis(a.getRelativePosition(), uStep, vStep, nStep)[1]);
            double vB = Math.abs(projectOntoBasis(b.getRelativePosition(), uStep, vStep, nStep)[1]);
            return Double.compare(vB, vA);
        });

        Map<Long, RasterizedBlock> byPosition = new LinkedHashMap<>();
        for (BlockSnapshot block : byDistanceFromHinge) {
            Vector rotated = anchor.clone().add(
                VectorMath.rotateAroundAxis(block.getRelativePosition(), hingeAxis, angleDegrees));
            Vector claimed = claimNearestAvailableCell(byPosition, rotated);
            if (claimed != null) {
                byPosition.put(packPosition(claimed), new RasterizedBlock(claimed, block));
            }
        }

        // 4. Test every candidate cell: inverse-rotate back into the closed frame, project onto
        // the gate's own (unrotated) basis, and check it lands within [0,W-1]x[0,H-1] - and,
        // critically, that its n-index is ~0. u/v alone only constrain the cell to *some* point
        // on the infinite line through the footprint along nStep; every scanned block is a single
        // layer (k=0 in i*uStep+j*vStep, with no k*nStep term at all - see
        // GateBlockScanTaskHandler.computeCellPosition), so the true rotated sheet is the n=0
        // slice specifically. (Multi-layer GeometryDepth>1 doors are a known, explicitly
        // unverified edge case - see ROTATION_GAP_FILL_DESIGN.md - not handled by this n=0 check.)
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Vector candidate = new Vector(x, y, z);
                    Vector local = candidate.clone().subtract(anchor);
                    Vector unrotated = VectorMath.rotateAroundAxis(local, hingeAxis, -angleDegrees);
                    double[] idx = projectOntoBasis(unrotated, uStep, vStep, nStep);

                    boolean withinFootprint = isRegionMode
                        ? pointInPolygon(idx[0], idx[1], footprintUV)
                        : withinAxis(idx[0], width) && withinAxis(idx[1], height);
                    if (!withinFootprint || Math.abs(idx[2]) > BOUNDS_EPSILON) {
                        continue;
                    }

                    BlockSnapshot source = byIndex.get(packIndex(Math.round(idx[0]), Math.round(idx[1])));
                    if (source != null) {
                        byPosition.putIfAbsent(packPosition(candidate), new RasterizedBlock(candidate, source));
                    }
                }
            }
        }

        result.addAll(byPosition.values());
        return result;
    }

    private static long packIndex(long i, long j) {
        return (i << 32) ^ (j & 0xFFFFFFFFL);
    }

    private static long packPosition(Vector position) {
        return packPositionCoords((long) Math.round(position.getX()), (long) Math.round(position.getY()),
            (long) Math.round(position.getZ()));
    }

    private static long packPositionCoords(long x, long y, long z) {
        // World coordinates comfortably fit in 26 bits (+/- ~33M); y needs far fewer.
        return (x & 0x3FFFFFFL) << 38 | (y & 0xFFFL) << 26 | (z & 0x3FFFFFFL);
    }

    /**
     * The true continuous position always lies within the unit cube spanned by floor/ceil of each
     * coordinate - one of those (up to) 8 corners is the exact integer cell a naive round would
     * pick, and the rest are its immediate neighbors. Returns whichever of the 8 is nearest to
     * {@code continuous} and not already claimed in {@code byPosition}, or null if every corner is
     * already taken (only possible under an extreme, multi-way collision).
     */
    private static Vector claimNearestAvailableCell(Map<Long, RasterizedBlock> byPosition, Vector continuous) {
        double[] xs = corners(continuous.getX());
        double[] ys = corners(continuous.getY());
        double[] zs = corners(continuous.getZ());

        Vector best = null;
        double bestDistanceSquared = Double.MAX_VALUE;
        for (double x : xs) {
            for (double y : ys) {
                for (double z : zs) {
                    if (byPosition.containsKey(packPositionCoords((long) x, (long) y, (long) z))) {
                        continue;
                    }
                    double dx = continuous.getX() - x;
                    double dy = continuous.getY() - y;
                    double dz = continuous.getZ() - z;
                    double distanceSquared = dx * dx + dy * dy + dz * dz;
                    if (distanceSquared < bestDistanceSquared) {
                        bestDistanceSquared = distanceSquared;
                        best = new Vector(x, y, z);
                    }
                }
            }
        }
        return best;
    }

    private static double[] corners(double value) {
        double floor = Math.floor(value);
        double ceil = Math.ceil(value);
        return floor == ceil ? new double[]{floor} : new double[]{floor, ceil};
    }

    /**
     * Calculate the step vector for linear motion.
     * This is the incremental displacement per frame.
     * 
     * @param gate The cached gate
     * @return Step vector (motionVector / totalFrames)
     */
    public static Vector calculateStepVector(CachedGateDoor gate) {
        if (gate == null) {
            return new Vector(0, 0, 0);
        }

        Vector motionVector = gate.getMotionVector();
        int totalFrames = gate.getAnimationDurationTicks();

        if (motionVector == null || totalFrames <= 0) {
            return new Vector(0, 0, 0);
        }

        return motionVector.clone().multiply(1.0 / totalFrames);
    }

    /**
     * Calculate the angle increment per frame for rotation motion.
     * 
     * @param gate The cached gate
     * @return Angle increment in degrees
     */
    public static double calculateAngleStep(CachedGateDoor gate) {
        if (gate == null) {
            return 0.0;
        }

        int maxAngle = gate.getRotationMaxAngleDegrees();
        int totalFrames = gate.getAnimationDurationTicks();

        if (totalFrames <= 0) {
            return 0.0;
        }

        return (double) maxAngle / totalFrames;
    }

    /**
     * Check if a block should be placed at a given frame.
     * Some gates may skip frames based on AnimationTickRate.
     * 
     * @param gate The cached gate
     * @param frame Current frame
     * @return True if block should be updated this frame
     */
    public static boolean shouldUpdateFrame(CachedGateDoor gate, int frame) {
        if (gate == null) {
            return false;
        }

        int tickRate = gate.getAnimationTickRate();
        
        // Always update first and last frame
        if (frame == 0 || frame == gate.getAnimationDurationTicks()) {
            return true;
        }

        // Otherwise, update every tickRate frames
        return frame % tickRate == 0;
    }
}

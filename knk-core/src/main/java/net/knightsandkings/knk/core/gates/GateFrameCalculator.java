package net.knightsandkings.knk.core.gates;

import net.knightsandkings.knk.core.domain.gates.BlockSnapshot;
import net.knightsandkings.knk.core.domain.gates.CachedGate;
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
    public static Vector calculateBlockPosition(CachedGate gate, BlockSnapshot block, int frame) {
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

        Vector position;
        if ("ROTATION".equals(motionType)) {
            Vector arcPos = calculateRotationPosition(gate, relativePos, progress);
            if (openTarget != null) {
                // finalPos(frame) = arcPos(frame) + (openScanPos - arcPos(totalFrames)) * progress(frame)
                // At progress=0 this is exactly arcPos(0) (today's closed position, no change); at
                // progress=1 it converges exactly on openTarget - see Decision in Mechanism 2.
                Vector arcPosFinal = calculateRotationPosition(gate, relativePos, 1.0);
                Vector correction = openTarget.clone().subtract(arcPosFinal).multiply(progress);
                position = arcPos.clone().add(correction);
            } else {
                position = arcPos;
            }
        } else if (openTarget != null) {
            // VERTICAL/LATERAL: the real motion already is a straight line, so a plain lerp
            // between the closed and open-scan positions is correct on its own (DUAL_SCAN_
            // ANIMATION_DESIGN.md's original design for this case).
            Vector closedPos = gate.getAnchorPoint().clone().add(relativePos);
            position = VectorMath.lerp(closedPos, openTarget, progress);
        } else {
            position = calculateLinearPosition(gate, relativePos, progress);
        }

        if (gate.isClipToGeometryBounds() && !isWithinGeometryBounds(gate, position)) {
            return null;
        }

        return position;
    }

    /**
     * Projects a world position back onto the gate's u/v/n basis and checks it against the
     * Width/Height/Depth box. Lets a door retract into a housing instead of sticking out of it.
     */
    public static boolean isWithinGeometryBounds(CachedGate gate, Vector worldPosition) {
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
     * @return {u, v, n} continuous (unrounded) indices
     */
    private static double[] projectOntoBasis(Vector local, Vector uStep, Vector vStep, Vector nStep) {
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

    private static boolean isWithinVerticalOpening(CachedGate gate, Vector anchor, Vector worldPosition) {
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
    private static Vector calculateLinearPosition(CachedGate gate, Vector relativePos, double progress) {
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
    private static Vector calculateRotationPosition(CachedGate gate, Vector relativePos, double progress) {
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
    public static double calculateRotationAngle(CachedGate gate, int frame) {
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
    public static List<RasterizedBlock> rasterizeRotationFrame(CachedGate gate, double angleDegrees) {
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

        // 1. Rotate the 4 local corners (in u/v index space) by angleDegrees around the hinge.
        int[][] cornerIndices = {{0, 0}, {width - 1, 0}, {0, height - 1}, {width - 1, height - 1}};
        Vector[] rotatedCorners = new Vector[cornerIndices.length];
        for (int c = 0; c < cornerIndices.length; c++) {
            Vector localCorner = uStep.clone().multiply(cornerIndices[c][0])
                .add(vStep.clone().multiply(cornerIndices[c][1]));
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
        // non-rasterized path) and flooring to the block it lands in - exactly like every other
        // block placement in this codebase (see GateBlockPlacer, which floors via getBlockX/Y/Z).
        // Step 4 below only *tests candidate world cells against the closed frame*: for a
        // diagonal hinge, a genuine source cell's true rotated position frequently doesn't
        // reverse-project back to an exact integer (u,v) for *any* nearby integer cell (the
        // rotation isn't lattice-preserving off cardinal axes), so step 4 alone silently drops
        // whole rows/columns near the geometry's edges - this is what produced the reported
        // "8-tall door only opens 6 blocks" bug: an entire column's blocks are, geometrically,
        // spaced *less than one block apart* once projected onto world X/Z after a diagonal-axis
        // rotation (e.g. 1/sqrt(2) per row for a 45-degree hinge), so several adjacent rows are
        // mathematically guaranteed to floor to the very same integer cell - this is an intrinsic
        // consequence of representing a continuously-rotated diagonal surface with unit blocks,
        // not something any lookup strategy can avoid entirely. What *is* avoidable is which row
        // wins that collision: processing farthest-from-hinge rows first (descending v-index) so
        // the door's true, farthest reach always survives a collision, rather than being clobbered
        // by whichever row processing happened to reach that cell first.
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
            // Rodrigues' formula involves cos(angleDegrees), which for a "nice" angle like 90
            // degrees is never bit-exact zero (Math.cos(Math.PI / 2) is ~6.12e-17, not 0) - so a
            // coordinate that's algebraically supposed to land exactly on an integer (e.g. the
            // hinge row, or any cardinal-axis rotation result) can come out a few ULPs *below* it
            // instead (observed: 0.9999999999999998). Math.floor has no tolerance for that - it
            // rounds such a value down a full block, silently colliding it with the block one cell
            // over. Nudging by a tiny epsilon (far larger than FP noise, far smaller than any real
            // fractional offset from a diagonal rotation) before flooring fixes that without
            // affecting genuinely fractional positions.
            Vector floored = new Vector(
                Math.floor(rotated.getX() + 1e-6), Math.floor(rotated.getY() + 1e-6), Math.floor(rotated.getZ() + 1e-6));
            byPosition.putIfAbsent(packPosition(floored), new RasterizedBlock(floored, block));
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

                    if (!withinAxis(idx[0], width) || !withinAxis(idx[1], height) || Math.abs(idx[2]) > BOUNDS_EPSILON) {
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
     * Calculate the step vector for linear motion.
     * This is the incremental displacement per frame.
     * 
     * @param gate The cached gate
     * @return Step vector (motionVector / totalFrames)
     */
    public static Vector calculateStepVector(CachedGate gate) {
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
    public static double calculateAngleStep(CachedGate gate) {
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
    public static boolean shouldUpdateFrame(CachedGate gate, int frame) {
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

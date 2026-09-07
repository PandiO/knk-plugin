package net.knightsandkings.knk.core.gates;

import net.knightsandkings.knk.core.domain.gates.BlockSnapshot;
import net.knightsandkings.knk.core.domain.gates.CachedGate;
import net.knightsandkings.knk.core.util.VectorMath;
import org.bukkit.util.Vector;

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
        
        Vector position;
        if ("ROTATION".equals(motionType)) {
            position = calculateRotationPosition(gate, relativePos, progress);
        } else {
            // VERTICAL or LATERAL - linear motion
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

        // Project onto the (possibly non-unit-length, e.g. diagonal) step vectors using the
        // standard oblique-basis formula local.dot(step)/|step|^2, which recovers the integer
        // width/height/depth index directly - unlike a unit-vector dot product, this stays exact
        // for a diagonal step like (1,0,1) whose length is sqrt(2), not 1.
        double uIndex = local.dot(uStep) / uStep.lengthSquared();
        double vIndex = local.dot(vStep) / vStep.lengthSquared();
        double nIndex = local.dot(nStep) / nStep.lengthSquared();

        return withinAxis(uIndex, gate.getGeometryWidth())
            && withinAxis(vIndex, gate.getGeometryHeight())
            && withinAxis(nIndex, gate.getGeometryDepth());
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
        int maxAngle = gate.getRotationMaxAngleDegrees();

        if (anchorPoint == null || relativePos == null || hingeAxis == null) {
            return anchorPoint != null ? anchorPoint.clone().add(relativePos) : new Vector(0, 0, 0);
        }

        // Calculate current rotation angle based on progress
        double currentAngle = maxAngle * progress;

        // Rotate the relative position around the hinge axis
        Vector rotatedPos = VectorMath.rotateAroundAxis(relativePos, hingeAxis, currentAngle);

        // Add to anchor point to get world position
        return anchorPoint.clone().add(rotatedPos);
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

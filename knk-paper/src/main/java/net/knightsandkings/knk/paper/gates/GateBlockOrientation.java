package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.core.domain.gates.CachedGate;
import net.knightsandkings.knk.core.util.VectorMath;
import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Orientable;
import org.bukkit.util.Vector;

import java.util.EnumMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Rotates a gate block's own facing/axis blockstate to match how far the door has physically
 * swung around its hinge, so e.g. a spruce_log placed with axis=y in the closed (scanned)
 * position ends up axis=x/z once the drawbridge has rotated 90 degrees open, instead of staying
 * frozen in its closed-position orientation.
 *
 * <p>Minecraft blockstates only support a handful of discrete facings, so a smooth 0-90 degree
 * sweep is approximated by rotating the block's orientation vector the same way its position is
 * rotated (see GateFrameCalculator.calculateRotationPosition) and snapping the result to the
 * nearest valid discrete axis/facing every frame.
 *
 * <p>Only {@link Orientable} (axis: logs, pillars, bone blocks, ...) and {@link Directional}
 * (facing: furnaces, stairs, ...) blockstates are handled. Anything else - including how the
 * blocks are laid out on the diagonal lattice itself - is unaffected.
 */
public final class GateBlockOrientation {
    private static final Logger LOGGER = Logger.getLogger(GateBlockOrientation.class.getName());

    private static final Map<Axis, Vector> AXIS_VECTORS = new EnumMap<>(Axis.class);
    private static final Map<BlockFace, Vector> FACE_VECTORS = new EnumMap<>(BlockFace.class);

    static {
        AXIS_VECTORS.put(Axis.X, new Vector(1, 0, 0));
        AXIS_VECTORS.put(Axis.Y, new Vector(0, 1, 0));
        AXIS_VECTORS.put(Axis.Z, new Vector(0, 0, 1));

        FACE_VECTORS.put(BlockFace.EAST, new Vector(1, 0, 0));
        FACE_VECTORS.put(BlockFace.WEST, new Vector(-1, 0, 0));
        FACE_VECTORS.put(BlockFace.UP, new Vector(0, 1, 0));
        FACE_VECTORS.put(BlockFace.DOWN, new Vector(0, -1, 0));
        FACE_VECTORS.put(BlockFace.SOUTH, new Vector(0, 0, 1));
        FACE_VECTORS.put(BlockFace.NORTH, new Vector(0, 0, -1));
    }

    private GateBlockOrientation() {
    }

    /**
     * Returns blockDataString with its axis/facing rotated by angleDegrees around the gate's
     * hinge axis, or unchanged if the block has neither, the gate has no hinge axis (non-rotation
     * gates), the angle is ~0, or the string fails to parse.
     */
    public static String applyRotation(String blockDataString, CachedGate gate, double angleDegrees) {
        if (blockDataString == null || blockDataString.isEmpty()) {
            return blockDataString;
        }

        Vector hingeAxis = gate != null ? gate.getHingeAxis() : null;
        if (hingeAxis == null || Math.abs(angleDegrees) < 0.001) {
            return blockDataString;
        }

        BlockData data;
        try {
            data = Bukkit.createBlockData(blockDataString);
        } catch (IllegalArgumentException e) {
            return blockDataString;
        }

        boolean changed = false;

        if (data instanceof Orientable orientable) {
            Axis rotated = rotateAxis(orientable.getAxis(), hingeAxis, angleDegrees);
            if (rotated != orientable.getAxis() && orientable.getAxes().contains(rotated)) {
                orientable.setAxis(rotated);
                changed = true;
            }
        } else if (data instanceof Directional directional) {
            BlockFace rotated = rotateFace(directional.getFacing(), hingeAxis, angleDegrees);
            if (rotated != null && rotated != directional.getFacing()) {
                try {
                    directional.setFacing(rotated);
                    changed = true;
                } catch (IllegalArgumentException e) {
                    // Not a valid facing for this particular block type - leave it as scanned.
                    LOGGER.fine("Gate block " + blockDataString + " does not support facing " + rotated);
                }
            }
        }

        return changed ? data.getAsString() : blockDataString;
    }

    /**
     * Rotates the world-direction vector of axis around hingeAxis by angleDegrees and snaps the
     * result to whichever of X/Y/Z that vector now points closest to. Pure vector math - no
     * Bukkit server/registry access - so it's directly unit-testable.
     */
    static Axis rotateAxis(Axis axis, Vector hingeAxis, double angleDegrees) {
        Vector original = AXIS_VECTORS.get(axis);
        Vector rotated = VectorMath.rotateAroundAxis(original, hingeAxis, angleDegrees);

        double ax = Math.abs(rotated.getX());
        double ay = Math.abs(rotated.getY());
        double az = Math.abs(rotated.getZ());

        if (ax >= ay && ax >= az) {
            return Axis.X;
        }
        if (ay >= ax && ay >= az) {
            return Axis.Y;
        }
        return Axis.Z;
    }

    /**
     * Rotates the world-direction vector of face around hingeAxis by angleDegrees and snaps the
     * result to whichever of the 6 cardinal BlockFaces that vector now points closest to. Pure
     * vector math - no Bukkit server/registry access - so it's directly unit-testable.
     */
    static BlockFace rotateFace(BlockFace face, Vector hingeAxis, double angleDegrees) {
        Vector original = FACE_VECTORS.get(face);
        if (original == null) {
            // Not one of the 6 cardinal faces (e.g. a diagonal rail shape) - leave untouched.
            return face;
        }

        Vector rotated = VectorMath.rotateAroundAxis(original, hingeAxis, angleDegrees);

        BlockFace best = null;
        double bestDot = -Double.MAX_VALUE;
        for (Map.Entry<BlockFace, Vector> entry : FACE_VECTORS.entrySet()) {
            double dot = entry.getValue().dot(rotated);
            if (dot > bestDot) {
                bestDot = dot;
                best = entry.getKey();
            }
        }
        return best;
    }
}

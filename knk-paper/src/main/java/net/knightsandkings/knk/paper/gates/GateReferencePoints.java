package net.knightsandkings.knk.paper.gates;

import org.bukkit.util.Vector;

import java.util.Locale;

/**
 * KNG-132: the gate spec measures both reference points from the anchor - ReferencePoint1 along
 * the door's width (bottom edge), ReferencePoint2 along its height, straight above the anchor - so
 * anchor -> ReferencePoint2 must be at right angles to anchor -> ReferencePoint1. Otherwise the
 * lattice steps are sheared: a (0,+7,-2) "height" is a single step, the PLANE_GRID scan stores a
 * row 7 up and 2 sideways instead of the door's real rows, and the door jams when it moves into the
 * blocks it never captured. The web app's GateDoor form refuses the same points
 * (knk-web-app utils/forms/gateGeometry.ts).
 */
public final class GateReferencePoints {
    private GateReferencePoints() {
    }

    /**
     * What is wrong with ReferencePoint2, or null when the points are fine or one is missing.
     * A ReferencePoint2 put above ReferencePoint1 (the opposite corner) gets the point it should be.
     */
    public static String referencePoint2Problem(Vector anchor, Vector ref1, Vector ref2) {
        if (anchor == null || ref1 == null || ref2 == null) {
            return null;
        }

        Vector width = rounded(ref1.clone().subtract(anchor));
        Vector height = rounded(ref2.clone().subtract(anchor));
        if (isZero(width) || isZero(height) || width.dot(height) == 0) {
            return null;
        }

        String rule = "Reference point 2 must be straight above the anchor (the door's height), at right angles to "
            + "the anchor -> reference point 1 edge (its width).";
        Vector ref1ToRef2 = rounded(ref2.clone().subtract(ref1));
        if (!isZero(ref1ToRef2) && ref1ToRef2.dot(width) == 0) {
            Vector suggested = anchor.clone().add(ref1ToRef2);
            return rule + " This one is above reference point 1; set it to (" + format(suggested.getX()) + ", "
                + format(suggested.getY()) + ", " + format(suggested.getZ()) + ") in the gate door form and scan again.";
        }
        return rule + " Fix it in the gate door form and scan again.";
    }

    private static Vector rounded(Vector v) {
        return new Vector(Math.round(v.getX()), Math.round(v.getY()), Math.round(v.getZ()));
    }

    private static boolean isZero(Vector v) {
        return v.getX() == 0 && v.getY() == 0 && v.getZ() == 0;
    }

    private static String format(double value) {
        return value == Math.rint(value)
            ? String.valueOf((long) value)
            : String.format(Locale.ROOT, "%.2f", value);
    }
}

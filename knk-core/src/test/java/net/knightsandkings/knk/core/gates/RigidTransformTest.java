package net.knightsandkings.knk.core.gates;

import net.knightsandkings.knk.core.util.VectorMath;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link RigidTransform}'s Kabsch fit - item 6.10 (docs/features/
 * gate-structure-animation/GATESTRUCTURE_QOL_IMPLEMENTATION_PLAN.md).
 */
class RigidTransformTest {

    private static final double EPSILON = 0.0001;

    private static List<Vector> somePoints() {
        // Deliberately not collinear/coplanar-degenerate and not symmetric about the origin, so
        // the fit is genuinely exercised (not just an identity/trivial case).
        List<Vector> points = new ArrayList<>();
        points.add(new Vector(0, 0, 0));
        points.add(new Vector(5, 0, 0));
        points.add(new Vector(0, 3, 0));
        points.add(new Vector(2, 1, 4));
        points.add(new Vector(-3, 2, 1));
        return points;
    }

    @Test
    void fit_ExactCorrespondences_RecoversTheKnownRotationAndTranslation() {
        List<Vector> from = somePoints();
        Vector axis = new Vector(1, 2, 3);
        double angleDegrees = 37.0;
        Vector translation = new Vector(10, -5, 20);

        List<Vector> to = new ArrayList<>();
        for (Vector p : from) {
            to.add(VectorMath.rotateAroundAxis(p, axis, angleDegrees).add(translation));
        }

        RigidTransform transform = RigidTransform.fit(from, to);

        for (int i = 0; i < from.size(); i++) {
            Vector recovered = transform.apply(from.get(i));
            Vector expected = to.get(i);
            assertEquals(expected.getX(), recovered.getX(), EPSILON);
            assertEquals(expected.getY(), recovered.getY(), EPSILON);
            assertEquals(expected.getZ(), recovered.getZ(), EPSILON);
        }
    }

    @Test
    void applyInverse_UndoesApply_ForAnArbitraryFittedTransform() {
        List<Vector> from = somePoints();
        Vector axis = new Vector(0, 1, 0);
        double angleDegrees = 73.0;
        Vector translation = new Vector(-2, 8, 6);

        List<Vector> to = new ArrayList<>();
        for (Vector p : from) {
            to.add(VectorMath.rotateAroundAxis(p, axis, angleDegrees).add(translation));
        }

        RigidTransform transform = RigidTransform.fit(from, to);

        Vector probe = new Vector(17, -4, 9);
        Vector roundTripped = transform.applyInverse(transform.apply(probe));
        assertEquals(probe.getX(), roundTripped.getX(), EPSILON);
        assertEquals(probe.getY(), roundTripped.getY(), EPSILON);
        assertEquals(probe.getZ(), roundTripped.getZ(), EPSILON);
    }

    @Test
    void fit_ReflectedCorrespondences_StillRecoversAProperRotationNotAReflection() {
        // A mirror (reflection) is not a rigid transform - a naive U*V^T can produce one when the
        // best linear alignment happens to be a reflection. The standard Kabsch fix (negate the
        // last singular vector) must kick in so the fitted transform is always a proper rotation.
        List<Vector> from = somePoints();
        List<Vector> to = new ArrayList<>();
        for (Vector p : from) {
            // Mirror across the X/Y plane (negate Z) - not achievable by any rotation alone.
            to.add(new Vector(p.getX(), p.getY(), -p.getZ()));
        }

        RigidTransform transform = RigidTransform.fit(from, to);

        // Recompose R from three orthonormal probe directions and confirm det(R) = +1 (a proper
        // rotation), not -1 (a reflection) - probe with the standard basis vectors via apply()
        // minus the (zero) translation implied by this symmetric point set.
        Vector rx = transform.apply(new Vector(1, 0, 0)).subtract(transform.apply(new Vector(0, 0, 0)));
        Vector ry = transform.apply(new Vector(0, 1, 0)).subtract(transform.apply(new Vector(0, 0, 0)));
        Vector rz = transform.apply(new Vector(0, 0, 1)).subtract(transform.apply(new Vector(0, 0, 0)));
        double det = rx.getX() * (ry.getY() * rz.getZ() - ry.getZ() * rz.getY())
            - rx.getY() * (ry.getX() * rz.getZ() - ry.getZ() * rz.getX())
            + rx.getZ() * (ry.getX() * rz.getY() - ry.getY() * rz.getX());
        assertEquals(1.0, det, EPSILON);
    }

    @Test
    void fit_ZeroPoints_ReturnsNull() {
        assertNull(RigidTransform.fit(List.of(), List.of()));
    }

    @Test
    void fit_OnePoint_ReturnsNull() {
        assertNull(RigidTransform.fit(List.of(new Vector(1, 2, 3)), List.of(new Vector(4, 5, 6))));
    }

    @Test
    void fit_TwoPoints_ReturnsNull() {
        assertNull(RigidTransform.fit(
            List.of(new Vector(0, 0, 0), new Vector(5, 0, 0)),
            List.of(new Vector(0, 0, 0), new Vector(0, 5, 0))));
    }

    @Test
    void fit_ThreeCollinearPoints_ReturnsNull() {
        List<Vector> from = List.of(new Vector(0, 0, 0), new Vector(1, 0, 0), new Vector(2, 0, 0));
        List<Vector> to = List.of(new Vector(0, 0, 0), new Vector(0, 1, 0), new Vector(0, 2, 0));

        assertNull(RigidTransform.fit(from, to));
    }

    @Test
    void fit_MismatchedSizes_ReturnsNull() {
        assertNull(RigidTransform.fit(somePoints(), List.of(new Vector(0, 0, 0))));
    }

    @Test
    void fit_NullInputs_ReturnsNull() {
        assertNull(RigidTransform.fit(null, somePoints()));
        assertNull(RigidTransform.fit(somePoints(), null));
    }
}

package net.knightsandkings.knk.core.util;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for VectorMath utility class.
 */
class VectorMathTest {

    private static final double EPSILON = 0.001;

    @Test
    void shouldRotateVectorAroundXAxis() {
        Vector v = new Vector(0, 1, 0);
        Vector rotated = VectorMath.rotateX(v, 90);
        
        assertVectorEquals(new Vector(0, 0, 1), rotated, EPSILON);
    }

    @Test
    void shouldRotateVectorAroundYAxis() {
        Vector v = new Vector(1, 0, 0);
        Vector rotated = VectorMath.rotateY(v, 90);
        
        assertVectorEquals(new Vector(0, 0, -1), rotated, EPSILON);
    }

    @Test
    void shouldRotateVectorAroundZAxis() {
        Vector v = new Vector(1, 0, 0);
        Vector rotated = VectorMath.rotateZ(v, 90);
        
        assertVectorEquals(new Vector(0, 1, 0), rotated, EPSILON);
    }

    @Test
    void shouldRotateVectorAroundArbitraryAxis() {
        Vector v = new Vector(1, 0, 0);
        Vector axis = new Vector(0, 1, 0); // Y-axis
        Vector rotated = VectorMath.rotateAroundAxis(v, axis, 90);
        
        assertVectorEquals(new Vector(0, 0, -1), rotated, EPSILON);
    }

    @Test
    void shouldHandleZeroRotation() {
        Vector v = new Vector(1, 2, 3);
        Vector rotated = VectorMath.rotateAroundAxis(v, new Vector(0, 1, 0), 0);
        
        assertVectorEquals(v, rotated, EPSILON);
    }

    @Test
    void shouldRotate360DegreesBackToOriginal() {
        Vector v = new Vector(1, 2, 3);
        Vector rotated = VectorMath.rotateX(v, 360);
        
        assertVectorEquals(v, rotated, EPSILON);
    }

    @Test
    void shouldRotateNegativeAngle() {
        Vector v = new Vector(0, 1, 0);
        Vector rotated = VectorMath.rotateX(v, -90);
        
        assertVectorEquals(new Vector(0, 0, -1), rotated, EPSILON);
    }

    @Test
    void shouldLerpBetweenVectors() {
        Vector start = new Vector(0, 0, 0);
        Vector end = new Vector(10, 10, 10);
        
        Vector midpoint = VectorMath.lerp(start, end, 0.5);
        assertVectorEquals(new Vector(5, 5, 5), midpoint, EPSILON);
        
        Vector quarterPoint = VectorMath.lerp(start, end, 0.25);
        assertVectorEquals(new Vector(2.5, 2.5, 2.5), quarterPoint, EPSILON);
    }

    @Test
    void shouldClampLerpParameter() {
        Vector start = new Vector(0, 0, 0);
        Vector end = new Vector(10, 10, 10);
        
        Vector result = VectorMath.lerp(start, end, 1.5);
        assertVectorEquals(end, result, EPSILON);
        
        Vector result2 = VectorMath.lerp(start, end, -0.5);
        assertVectorEquals(start, result2, EPSILON);
    }

    @Test
    void shouldCalculateAngleBetweenVectors() {
        Vector v1 = new Vector(1, 0, 0);
        Vector v2 = new Vector(0, 1, 0);
        
        double angle = VectorMath.angleBetween(v1, v2);
        assertEquals(90.0, angle, EPSILON);
    }

    @Test
    void shouldCalculateZeroAngleForParallelVectors() {
        Vector v1 = new Vector(1, 0, 0);
        Vector v2 = new Vector(2, 0, 0);
        
        double angle = VectorMath.angleBetween(v1, v2);
        assertEquals(0.0, angle, EPSILON);
    }

    @Test
    void shouldCalculate180AngleForOppositeVectors() {
        Vector v1 = new Vector(1, 0, 0);
        Vector v2 = new Vector(-1, 0, 0);
        
        double angle = VectorMath.angleBetween(v1, v2);
        assertEquals(180.0, angle, EPSILON);
    }

    @Test
    void shouldThrowExceptionForNullVector() {
        assertThrows(IllegalArgumentException.class, () -> {
            VectorMath.rotateAroundAxis(null, new Vector(0, 1, 0), 90);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            VectorMath.rotateAroundAxis(new Vector(1, 0, 0), null, 90);
        });
    }

    @Test
    void shouldThrowExceptionForNullVectorsInLerp() {
        assertThrows(IllegalArgumentException.class, () -> {
            VectorMath.lerp(null, new Vector(1, 1, 1), 0.5);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            VectorMath.lerp(new Vector(0, 0, 0), null, 0.5);
        });
    }

    @Test
    void shouldReduceDiagonalDeltaToPrimitiveStep() {
        assertVectorEquals(new Vector(1, 0, 1), VectorMath.primitiveLatticeStep(new Vector(4, 0, 4)), EPSILON);
    }

    @Test
    void shouldReduceNonUniformDeltaToPrimitiveStep() {
        assertVectorEquals(new Vector(3, 0, 1), VectorMath.primitiveLatticeStep(new Vector(6, 0, 2)), EPSILON);
    }

    @Test
    void shouldReduceCardinalDeltaToUnitStep() {
        assertVectorEquals(new Vector(0, 1, 0), VectorMath.primitiveLatticeStep(new Vector(0, 5, 0)), EPSILON);
        assertVectorEquals(new Vector(1, 0, 0), VectorMath.primitiveLatticeStep(new Vector(1, 0, 0)), EPSILON);
    }

    @Test
    void shouldPreserveSignWhenReducingPrimitiveStep() {
        assertVectorEquals(new Vector(-1, 0, 1), VectorMath.primitiveLatticeStep(new Vector(-4, 0, 4)), EPSILON);
    }

    @Test
    void shouldReturnZeroVectorForDegenerateDelta() {
        assertVectorEquals(new Vector(0, 0, 0), VectorMath.primitiveLatticeStep(new Vector(0, 0, 0)), EPSILON);
    }

    @Test
    void shouldToleratePlacementNoiseWhenReducingPrimitiveStep() {
        assertVectorEquals(new Vector(1, 0, -1), VectorMath.primitiveLatticeStep(new Vector(2.98, 0, -2.99)), EPSILON);
    }

    @Test
    void shouldThrowExceptionForNullDeltaInPrimitiveLatticeStep() {
        assertThrows(IllegalArgumentException.class, () -> VectorMath.primitiveLatticeStep(null));
    }

    @Test
    void rotateAroundAxisShouldBeUnaffectedByAxisMagnitudeForDiagonalHingeAxis() {
        // DRAWBRIDGE-style hinge rotation always passes the gate's unit nAxis as the rotation
        // axis, never the new lattice nStep - this documents that rotateAroundAxis normalizes
        // its axis internally, so it would produce the identical rotation either way. A diagonal
        // hinge (e.g. a gate facing north-east) is therefore unaffected by introducing uStep/vStep/nStep.
        Vector v = new Vector(1, 0, 0);
        Vector unitDiagonalAxis = new Vector(1.0 / Math.sqrt(2), 0, 1.0 / Math.sqrt(2));
        Vector nonUnitDiagonalAxis = new Vector(1, 0, 1); // same direction as unitDiagonalAxis, length sqrt(2)

        Vector rotatedByUnitAxis = VectorMath.rotateAroundAxis(v, unitDiagonalAxis, 90);
        Vector rotatedByNonUnitAxis = VectorMath.rotateAroundAxis(v, nonUnitDiagonalAxis, 90);

        assertVectorEquals(rotatedByUnitAxis, rotatedByNonUnitAxis, EPSILON);
    }

    /**
     * Helper method to assert vectors are equal within epsilon tolerance.
     */
    private void assertVectorEquals(Vector expected, Vector actual, double epsilon) {
        assertEquals(expected.getX(), actual.getX(), epsilon, 
            "X component mismatch: expected " + expected.getX() + " but got " + actual.getX());
        assertEquals(expected.getY(), actual.getY(), epsilon, 
            "Y component mismatch: expected " + expected.getY() + " but got " + actual.getY());
        assertEquals(expected.getZ(), actual.getZ(), epsilon, 
            "Z component mismatch: expected " + expected.getZ() + " but got " + actual.getZ());
    }
}

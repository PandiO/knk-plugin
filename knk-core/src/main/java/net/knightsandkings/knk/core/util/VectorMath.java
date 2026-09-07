package net.knightsandkings.knk.core.util;

import org.bukkit.util.Vector;

/**
 * Utility class for advanced vector mathematics operations.
 * Provides 3D rotation calculations using Rodrigues' rotation formula.
 */
public class VectorMath {

    /**
     * Rotate a vector around an arbitrary axis using Rodrigues' rotation formula.
     * 
     * Formula: v' = v*cos(θ) + (k × v)*sin(θ) + k*(k·v)*(1 - cos(θ))
     * Where:
     * - v is the vector to rotate
     * - k is the unit axis of rotation
     * - θ is the angle in degrees
     * 
     * @param v Vector to rotate
     * @param axis Axis to rotate around (will be normalized)
     * @param angleDegrees Rotation angle in degrees (positive = right-hand rule)
     * @return Rotated vector
     */
    public static Vector rotateAroundAxis(Vector v, Vector axis, double angleDegrees) {
        if (v == null || axis == null) {
            throw new IllegalArgumentException("Vector and axis cannot be null");
        }

        // Handle zero rotation
        if (Math.abs(angleDegrees) < 0.001) {
            return v.clone();
        }

        // Normalize axis
        Vector k = axis.clone().normalize();

        // Convert angle to radians
        double angleRadians = Math.toRadians(angleDegrees);
        double cosTheta = Math.cos(angleRadians);
        double sinTheta = Math.sin(angleRadians);

        // Calculate k dot v (scalar product)
        double kDotV = k.dot(v);

        // Calculate k cross v
        Vector kCrossV = k.clone().crossProduct(v);

        // Apply Rodrigues' formula: v' = v*cos(θ) + (k × v)*sin(θ) + k*(k·v)*(1 - cos(θ))
        Vector result = new Vector();
        
        // Term 1: v * cos(θ)
        result.add(v.clone().multiply(cosTheta));
        
        // Term 2: (k × v) * sin(θ)
        result.add(kCrossV.multiply(sinTheta));
        
        // Term 3: k * (k·v) * (1 - cos(θ))
        result.add(k.clone().multiply(kDotV * (1 - cosTheta)));

        return result;
    }

    /**
     * Rotate a vector around the X-axis.
     * 
     * @param v Vector to rotate
     * @param angleDegrees Rotation angle in degrees
     * @return Rotated vector
     */
    public static Vector rotateX(Vector v, double angleDegrees) {
        return rotateAroundAxis(v, new Vector(1, 0, 0), angleDegrees);
    }

    /**
     * Rotate a vector around the Y-axis.
     * 
     * @param v Vector to rotate
     * @param angleDegrees Rotation angle in degrees
     * @return Rotated vector
     */
    public static Vector rotateY(Vector v, double angleDegrees) {
        return rotateAroundAxis(v, new Vector(0, 1, 0), angleDegrees);
    }

    /**
     * Rotate a vector around the Z-axis.
     * 
     * @param v Vector to rotate
     * @param angleDegrees Rotation angle in degrees
     * @return Rotated vector
     */
    public static Vector rotateZ(Vector v, double angleDegrees) {
        return rotateAroundAxis(v, new Vector(0, 0, 1), angleDegrees);
    }

    /**
     * Linearly interpolate between two vectors.
     * 
     * @param start Start vector
     * @param end End vector
     * @param t Interpolation factor (0.0 to 1.0)
     * @return Interpolated vector
     */
    public static Vector lerp(Vector start, Vector end, double t) {
        if (start == null || end == null) {
            throw new IllegalArgumentException("Start and end vectors cannot be null");
        }

        t = Math.max(0.0, Math.min(1.0, t)); // Clamp t to [0, 1]

        return new Vector(
            start.getX() + (end.getX() - start.getX()) * t,
            start.getY() + (end.getY() - start.getY()) * t,
            start.getZ() + (end.getZ() - start.getZ()) * t
        );
    }

    /**
     * Calculate the angle between two vectors in degrees.
     *
     * @param v1 First vector
     * @param v2 Second vector
     * @return Angle in degrees (0 to 180)
     */
    public static double angleBetween(Vector v1, Vector v2) {
        if (v1 == null || v2 == null) {
            throw new IllegalArgumentException("Vectors cannot be null");
        }

        double dot = v1.dot(v2);
        double lengthProduct = v1.length() * v2.length();

        if (lengthProduct == 0) {
            return 0;
        }

        double cosAngle = dot / lengthProduct;
        // Clamp to avoid numerical errors
        cosAngle = Math.max(-1.0, Math.min(1.0, cosAngle));

        return Math.toDegrees(Math.acos(cosAngle));
    }

    /**
     * Reduces an integer-valued delta (e.g. a reference-point offset) to the shortest integer
     * vector pointing in the exact same direction, by dividing out the GCD of its components.
     * Unlike normalize(), this preserves Minecraft's integer block lattice: a cardinal delta like
     * (4,0,0) reduces to (1,0,0) (one block per step, same as normalize()), but a diagonal delta
     * like (4,0,4) reduces to (1,0,1) - the true nearest diagonal neighbor - instead of a unit-length
     * (0.7071,0,0.7071) that doesn't land on an adjacent lattice block when stepped by an integer index.
     *
     * @param delta Vector to reduce; components are rounded to the nearest integer first
     * @return The shortest same-direction integer vector, or (0,0,0) if delta rounds to zero
     */
    public static Vector primitiveLatticeStep(Vector delta) {
        if (delta == null) {
            throw new IllegalArgumentException("Delta cannot be null");
        }

        long dx = Math.round(delta.getX());
        long dy = Math.round(delta.getY());
        long dz = Math.round(delta.getZ());

        long g = gcd(gcd(Math.abs(dx), Math.abs(dy)), Math.abs(dz));
        if (g == 0) {
            return new Vector(0, 0, 0);
        }

        return new Vector(dx / g, dy / g, dz / g);
    }

    private static long gcd(long a, long b) {
        while (b != 0) {
            long t = b;
            b = a % b;
            a = t;
        }
        return a;
    }
}

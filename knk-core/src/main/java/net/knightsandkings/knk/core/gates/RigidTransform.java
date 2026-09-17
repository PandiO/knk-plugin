package net.knightsandkings.knk.core.gates;

import org.bukkit.util.Vector;

import java.util.List;

/**
 * A best-fit rigid rotation+translation between two corresponding 3D point sets, computed via the
 * Kabsch algorithm (least-squares rigid registration). Introduced for item 6.10 (docs/features/
 * gate-structure-animation/GATESTRUCTURE_QOL_IMPLEMENTATION_PLAN.md, Decision 7 in
 * ROTATION_GAP_FILL_DESIGN.md) to replace Mechanism 2's per-block nearest-neighbor correction for
 * {@code ROTATION} gates - that per-block scheme let neighboring blocks drift by different amounts
 * toward their own individually-paired open-scan target, which is what produced the reported
 * non-rigid "fluid" swing. A single shared transform, applied identically to every block, cannot
 * disagree with itself between neighbors by construction.
 *
 * <p>Pure math, no Bukkit World/Block access - {@link org.bukkit.util.Vector} is just this
 * codebase's 3D vector type (already used throughout {@code knk-core}'s other pure gate-geometry
 * classes, e.g. {@link GateBlockPairing}/{@link GateFrameCalculator}), so this is directly
 * unit-testable like its siblings.
 */
public final class RigidTransform {

    private static final double EPSILON = 1e-9;

    // Rotation matrix, row-major: r[row][col]. Orthogonal with det=+1 (a proper rotation, never a
    // reflection - see the reflection-correction step in solveOptimalRotation).
    private final double[][] r;
    private final Vector t;

    private RigidTransform(double[][] r, Vector t) {
        this.r = r;
        this.t = t;
    }

    /** worldPos' = R * worldPos + t */
    public Vector apply(Vector point) {
        return new Vector(
            r[0][0] * point.getX() + r[0][1] * point.getY() + r[0][2] * point.getZ() + t.getX(),
            r[1][0] * point.getX() + r[1][1] * point.getY() + r[1][2] * point.getZ() + t.getY(),
            r[2][0] * point.getX() + r[2][1] * point.getY() + r[2][2] * point.getZ() + t.getZ()
        );
    }

    /**
     * worldPos = R^-1 * (point - t) = R^T * (point - t) - a rotation matrix's inverse is its
     * transpose, so no separate matrix inversion is needed. Used to synthesize a closed-side start
     * point for a block that only exists in the open scan (Decision 7, option 1, step 3).
     */
    public Vector applyInverse(Vector point) {
        double dx = point.getX() - t.getX();
        double dy = point.getY() - t.getY();
        double dz = point.getZ() - t.getZ();
        return new Vector(
            r[0][0] * dx + r[1][0] * dy + r[2][0] * dz,
            r[0][1] * dx + r[1][1] * dy + r[2][1] * dz,
            r[0][2] * dx + r[1][2] * dy + r[2][2] * dz
        );
    }

    /**
     * Fits the best-fit (least-squares) rigid rotation+translation mapping {@code fromPoints} onto
     * {@code toPoints}, via the Kabsch algorithm: center both point sets on their own centroid,
     * SVD the cross-covariance matrix, recover the rotation from {@code U/V} with the standard
     * reflection correction, then derive the translation from the two centroids.
     *
     * @param fromPoints source points (e.g. closed-state world positions)
     * @param toPoints corresponding destination points (e.g. paired open-state world positions),
     *     index-aligned with {@code fromPoints}
     * @return the fitted transform, or {@code null} when the input is degenerate: fewer than 3
     *     correspondence pairs, or the points don't span at least a 2D spread (all collinear/
     *     coincident) - there's nothing for a 3D rotation fit to determine in either case, "nothing
     *     to fit" per Decision 7's explicit fallback.
     */
    public static RigidTransform fit(List<Vector> fromPoints, List<Vector> toPoints) {
        if (fromPoints == null || toPoints == null || fromPoints.size() != toPoints.size() || fromPoints.size() < 3) {
            return null;
        }

        int n = fromPoints.size();
        Vector fromCentroid = centroid(fromPoints);
        Vector toCentroid = centroid(toPoints);

        // Cross-covariance H = sum_i (from_i - fromCentroid) (to_i - toCentroid)^T
        double[][] h = new double[3][3];
        for (int i = 0; i < n; i++) {
            double px = fromPoints.get(i).getX() - fromCentroid.getX();
            double py = fromPoints.get(i).getY() - fromCentroid.getY();
            double pz = fromPoints.get(i).getZ() - fromCentroid.getZ();
            double qx = toPoints.get(i).getX() - toCentroid.getX();
            double qy = toPoints.get(i).getY() - toCentroid.getY();
            double qz = toPoints.get(i).getZ() - toCentroid.getZ();
            double[] p = {px, py, pz};
            double[] q = {qx, qy, qz};
            for (int a = 0; a < 3; a++) {
                for (int b = 0; b < 3; b++) {
                    h[a][b] += p[a] * q[b];
                }
            }
        }

        double[][] r = solveOptimalRotation(h);
        if (r == null) {
            return null;
        }

        Vector rotatedFromCentroid = applyMatrix(r, fromCentroid);
        Vector translation = toCentroid.clone().subtract(rotatedFromCentroid);
        return new RigidTransform(r, translation);
    }

    /**
     * Recovers the optimal rotation matrix from the 3x3 cross-covariance matrix {@code h} via SVD
     * ({@code h = U * Sigma * V^T}, obtained here by eigen-decomposing the symmetric {@code h^T h}
     * to get {@code V}/singular values, then {@code U_i = h*v_i / sigma_i}), with the standard
     * Kabsch reflection fix: if the naive {@code R = V * U^T} has {@code det(R) < 0} (a reflection,
     * not a proper rotation), negate the last column of {@code V} (the smallest singular value's
     * component) before recomposing.
     *
     * @return the 3x3 rotation matrix, or {@code null} if {@code h} doesn't have at least 2
     *     independent (non-collinear) directions - see {@link #fit}'s degenerate-input contract.
     */
    private static double[][] solveOptimalRotation(double[][] h) {
        double[][] hth = multiplyATA(h);
        double[] eigenvalues = new double[3];
        double[][] eigenvectors = new double[3][3];
        jacobiEigenDecomposition(hth, eigenvectors, eigenvalues);

        // Sort eigenpairs descending by eigenvalue (largest singular value first).
        int[] order = {0, 1, 2};
        for (int i = 0; i < 3; i++) {
            for (int j = i + 1; j < 3; j++) {
                if (eigenvalues[order[j]] > eigenvalues[order[i]]) {
                    int tmp = order[i];
                    order[i] = order[j];
                    order[j] = tmp;
                }
            }
        }

        double[][] v = new double[3][3];
        double[] sigma = new double[3];
        for (int col = 0; col < 3; col++) {
            int src = order[col];
            sigma[col] = Math.sqrt(Math.max(0, eigenvalues[src]));
            for (int row = 0; row < 3; row++) {
                v[row][col] = eigenvectors[row][src];
            }
        }

        // Need at least 2 non-negligible singular values (the point set spans at least a 2D
        // plane) - with only 1 (or 0), the rotation around the missing axis/axes is genuinely
        // undetermined by the data (collinear or coincident correspondence points).
        if (sigma[0] <= EPSILON || sigma[1] <= sigma[0] * EPSILON) {
            return null;
        }

        double[][] u = new double[3][3];
        for (int col = 0; col < 2; col++) {
            double[] hv = multiplyMatrixColumn(h, v, col);
            for (int row = 0; row < 3; row++) {
                u[row][col] = hv[row] / sigma[col];
            }
        }
        // Third column completed via cross product (not H*v3/sigma3, which is unreliable when
        // sigma3 is small/zero) to guarantee U stays orthonormal.
        double[] u0 = {u[0][0], u[1][0], u[2][0]};
        double[] u1 = {u[0][1], u[1][1], u[2][1]};
        double[] u2 = cross(u0, u1);
        u[0][2] = u2[0];
        u[1][2] = u2[1];
        u[2][2] = u2[2];

        double[][] rCandidate = multiply(v, transpose(u));
        if (determinant(rCandidate) < 0) {
            // Standard Kabsch reflection fix: negate the last (smallest-singular-value) column of
            // V, then recompose - flips the candidate from a reflection to the nearest proper
            // rotation.
            v[0][2] = -v[0][2];
            v[1][2] = -v[1][2];
            v[2][2] = -v[2][2];
            rCandidate = multiply(v, transpose(u));
        }
        return rCandidate;
    }

    private static Vector centroid(List<Vector> points) {
        double x = 0, y = 0, z = 0;
        for (Vector p : points) {
            x += p.getX();
            y += p.getY();
            z += p.getZ();
        }
        int n = points.size();
        return new Vector(x / n, y / n, z / n);
    }

    private static Vector applyMatrix(double[][] m, Vector p) {
        return new Vector(
            m[0][0] * p.getX() + m[0][1] * p.getY() + m[0][2] * p.getZ(),
            m[1][0] * p.getX() + m[1][1] * p.getY() + m[1][2] * p.getZ(),
            m[2][0] * p.getX() + m[2][1] * p.getY() + m[2][2] * p.getZ()
        );
    }

    /** A^T * A for a 3x3 matrix - always symmetric positive semi-definite. */
    private static double[][] multiplyATA(double[][] a) {
        double[][] result = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double sum = 0;
                for (int k = 0; k < 3; k++) {
                    sum += a[k][i] * a[k][j];
                }
                result[i][j] = sum;
            }
        }
        return result;
    }

    private static double[] multiplyMatrixColumn(double[][] m, double[][] colSource, int col) {
        double cx = colSource[0][col];
        double cy = colSource[1][col];
        double cz = colSource[2][col];
        return new double[]{
            m[0][0] * cx + m[0][1] * cy + m[0][2] * cz,
            m[1][0] * cx + m[1][1] * cy + m[1][2] * cz,
            m[2][0] * cx + m[2][1] * cy + m[2][2] * cz
        };
    }

    private static double[][] multiply(double[][] a, double[][] b) {
        double[][] result = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double sum = 0;
                for (int k = 0; k < 3; k++) {
                    sum += a[i][k] * b[k][j];
                }
                result[i][j] = sum;
            }
        }
        return result;
    }

    private static double[][] transpose(double[][] a) {
        double[][] result = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                result[i][j] = a[j][i];
            }
        }
        return result;
    }

    private static double determinant(double[][] m) {
        return m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
            - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
            + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0]
        };
    }

    /**
     * Classic cyclic Jacobi eigenvalue algorithm for a symmetric 3x3 matrix - simple and
     * numerically robust at this fixed, tiny size (converges within a handful of sweeps).
     * {@code eigenvectors}' columns are the eigenvectors, index-aligned with {@code eigenvalues}
     * (unsorted - callers sort by eigenvalue themselves, see {@link #solveOptimalRotation}).
     */
    private static void jacobiEigenDecomposition(double[][] symmetric, double[][] eigenvectors, double[] eigenvalues) {
        double[][] a = new double[3][3];
        for (int i = 0; i < 3; i++) {
            a[i] = symmetric[i].clone();
        }
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                eigenvectors[i][j] = (i == j) ? 1.0 : 0.0;
            }
        }

        for (int sweep = 0; sweep < 100; sweep++) {
            double off = Math.abs(a[0][1]) + Math.abs(a[0][2]) + Math.abs(a[1][2]);
            if (off < 1e-14) {
                break;
            }

            for (int p = 0; p < 3; p++) {
                for (int q = p + 1; q < 3; q++) {
                    if (Math.abs(a[p][q]) < 1e-300) {
                        continue;
                    }
                    double theta = (a[q][q] - a[p][p]) / (2 * a[p][q]);
                    double t = Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
                    if (theta == 0) {
                        t = 1;
                    }
                    double c = 1 / Math.sqrt(t * t + 1);
                    double s = t * c;

                    double app = a[p][p];
                    double aqq = a[q][q];
                    double apq = a[p][q];
                    a[p][p] = c * c * app - 2 * s * c * apq + s * s * aqq;
                    a[q][q] = s * s * app + 2 * s * c * apq + c * c * aqq;
                    a[p][q] = 0;
                    a[q][p] = 0;

                    for (int i = 0; i < 3; i++) {
                        if (i != p && i != q) {
                            double aip = a[i][p];
                            double aiq = a[i][q];
                            a[i][p] = c * aip - s * aiq;
                            a[p][i] = a[i][p];
                            a[i][q] = s * aip + c * aiq;
                            a[q][i] = a[i][q];
                        }
                    }

                    for (int i = 0; i < 3; i++) {
                        double vip = eigenvectors[i][p];
                        double viq = eigenvectors[i][q];
                        eigenvectors[i][p] = c * vip - s * viq;
                        eigenvectors[i][q] = s * vip + c * viq;
                    }
                }
            }
        }

        for (int i = 0; i < 3; i++) {
            eigenvalues[i] = a[i][i];
        }
    }
}

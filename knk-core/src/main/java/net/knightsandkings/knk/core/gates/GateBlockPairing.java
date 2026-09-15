package net.knightsandkings.knk.core.gates;

import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pairs each closed-state gate block with its nearest open-state counterpart by 3D world-space
 * distance, per Mechanism 2 (manually-scanned open state) in
 * docs/features/gate-structure-animation/ROTATION_GAP_FILL_DESIGN.md.
 *
 * <p>Decision 2 originally shipped a greedy nearest-neighbor matcher for v1. <strong>Updated
 * during item 6.7's live testing (2026-09-15)</strong>, in two stages:
 *
 * <ol>
 *   <li>The original matched in closed-block <em>list order</em> (each closed block claimed
 *       whichever open block was still available when its own turn came, "first-claimed-wins").
 *       A dense REGION-mode capture (many open candidates sitting within a fraction of a block of
 *       each other) exposed a real flaw: a closed block processed early could claim an open block
 *       that a later-processed closed block was actually far closer to. Symptom: the animated
 *       plane moved like "fluid" instead of a rigid door, since neighboring blocks' paired
 *       targets no longer agreed.
 *   <li>The first attempted fix (sort every candidate pair by distance globally, claim greedily
 *       in that order) removed the list-order dependency but is still only a local approximation
 *       of the true minimum-total-distance matching - greedily locking in the single closest edge
 *       first can force a worse total than swapping to a different pairing. A small crafted
 *       counterexample already existed in the test suite
 *       ({@code GateLoaderAdapterTest#loadAndCacheStructure_RotationGateWithOpenedSnapshots_PairsByNearestWorldDistance}):
 *       two near-symmetric closed/open pairs where claiming the single globally-nearest edge
 *       first ({@code closedFar-openNear}, distance&sup2;=495025) locks out the pairing that
 *       actually minimizes the combined total ({@code closedNear-openNear} +
 *       {@code closedFar-openFar}, 500000+500000=1000000, versus the greedy choice's
 *       505025+495025=1000050).
 * </ol>
 *
 * <p>This now solves the true minimum-total-distance bipartite matching (the assignment problem)
 * via the Hungarian algorithm - O(n&sup2;&middot;m) for an n&times;m cost matrix, trivial at gate
 * scale (tens of blocks) and computed once per door load, never per animation tick. When the two
 * lists differ in size, the smaller side is matched completely and the leftover blocks on the
 * larger side are simply left unpaired, exactly as before.
 */
public final class GateBlockPairing {

    /** Sentinel "infinity" for the Hungarian algorithm's internal potentials - see {@link #solveAssignment}. */
    private static final double UNREACHABLE = Double.MAX_VALUE / 4;

    private GateBlockPairing() {
    }

    /**
     * @param closedWorldPositions Each closed block's absolute world position at rest (index-aligned with the caller's block list)
     * @param openWorldPositions Each open-scan block's absolute world position (index-aligned with the caller's open-block list)
     * @return closed-list-index -> open-list-index for every successfully paired closed block; unpaired closed blocks have no entry
     */
    public static Map<Integer, Integer> pairNearestNeighbor(List<Vector> closedWorldPositions, List<Vector> openWorldPositions) {
        Map<Integer, Integer> pairing = new HashMap<>();
        if (closedWorldPositions == null || openWorldPositions == null) {
            return pairing;
        }

        List<Integer> closedIndices = new ArrayList<>();
        for (int i = 0; i < closedWorldPositions.size(); i++) {
            if (closedWorldPositions.get(i) != null) {
                closedIndices.add(i);
            }
        }
        List<Integer> openIndices = new ArrayList<>();
        for (int j = 0; j < openWorldPositions.size(); j++) {
            if (openWorldPositions.get(j) != null) {
                openIndices.add(j);
            }
        }
        if (closedIndices.isEmpty() || openIndices.isEmpty()) {
            return pairing;
        }

        // The Hungarian algorithm below assumes rows <= cols so every row can be matched; when
        // there are more closed blocks than open blocks, solve it the other way around (match
        // every open block to a distinct closed block) and invert the result, rather than padding
        // with fake zero-cost rows that would corrupt the true minimum.
        boolean transposed = closedIndices.size() > openIndices.size();
        List<Integer> rows = transposed ? openIndices : closedIndices;
        List<Integer> cols = transposed ? closedIndices : openIndices;
        List<Vector> rowPositions = transposed ? openWorldPositions : closedWorldPositions;
        List<Vector> colPositions = transposed ? closedWorldPositions : openWorldPositions;

        int n = rows.size();
        int m = cols.size();
        double[][] cost = new double[n + 1][m + 1];
        for (int i = 1; i <= n; i++) {
            Vector rowPos = rowPositions.get(rows.get(i - 1));
            for (int j = 1; j <= m; j++) {
                Vector colPos = colPositions.get(cols.get(j - 1));
                cost[i][j] = rowPos.distanceSquared(colPos);
            }
        }

        int[] rowForCol = solveAssignment(cost, n, m);

        for (int j = 1; j <= m; j++) {
            if (rowForCol[j] == 0) {
                continue;
            }
            int rowOriginalIndex = rows.get(rowForCol[j] - 1);
            int colOriginalIndex = cols.get(j - 1);
            if (transposed) {
                pairing.put(colOriginalIndex, rowOriginalIndex);
            } else {
                pairing.put(rowOriginalIndex, colOriginalIndex);
            }
        }

        return pairing;
    }

    /**
     * Classic O(n&sup2;&middot;m) Hungarian algorithm (Kuhn-Munkres, shortest-augmenting-path
     * formulation) for a rectangular cost matrix with n &lt;= m. 1-indexed throughout (index 0 is
     * a sentinel), matching the standard reference implementation this is transcribed from.
     *
     * @return rowForCol[1..m]: the row (1-indexed) matched to each column, or 0 if that column is
     *     unmatched - always true for (m - n) of the columns, since every row ends up matched but
     *     there are more columns than rows.
     */
    private static int[] solveAssignment(double[][] cost, int n, int m) {
        double[] u = new double[n + 1];
        double[] v = new double[m + 1];
        int[] p = new int[m + 1];
        int[] way = new int[m + 1];

        for (int i = 1; i <= n; i++) {
            p[0] = i;
            int j0 = 0;
            double[] minv = new double[m + 1];
            Arrays.fill(minv, UNREACHABLE);
            boolean[] used = new boolean[m + 1];
            do {
                used[j0] = true;
                int i0 = p[j0];
                double delta = UNREACHABLE;
                int j1 = -1;
                for (int j = 1; j <= m; j++) {
                    if (!used[j]) {
                        double cur = cost[i0][j] - u[i0] - v[j];
                        if (cur < minv[j]) {
                            minv[j] = cur;
                            way[j] = j0;
                        }
                        if (minv[j] < delta) {
                            delta = minv[j];
                            j1 = j;
                        }
                    }
                }
                for (int j = 0; j <= m; j++) {
                    if (used[j]) {
                        u[p[j]] += delta;
                        v[j] -= delta;
                    } else {
                        minv[j] -= delta;
                    }
                }
                j0 = j1;
            } while (p[j0] != 0);
            do {
                int j1 = way[j0];
                p[j0] = p[j1];
                j0 = j1;
            } while (j0 != 0);
        }

        return p;
    }
}

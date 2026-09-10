package net.knightsandkings.knk.core.gates;

import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pairs each closed-state gate block with its nearest open-state counterpart by 3D world-space
 * distance, per Mechanism 2 (manually-scanned open state) in
 * docs/features/gate-structure-animation/ROTATION_GAP_FILL_DESIGN.md.
 *
 * <p>Decision 2: ship greedy nearest-neighbor for v1, no smarter (optimal-assignment) algorithm.
 * Decision (many-to-one tie-break, pinned down here): closed blocks are claimed in list order
 * (the caller's list order - GateLoaderAdapter passes them in SortOrder order), so the first
 * closed block to want a given open block gets it; a later closed block that would have picked
 * the same open block instead falls back to its next-nearest still-unclaimed one, or is left
 * unpaired if none remain - "first-claimed-wins", matching Decision 1(a)'s existing fallback
 * (an unpaired block simply pops in at the final frame, same as today's behavior).
 */
public final class GateBlockPairing {

    private GateBlockPairing() {
    }

    /**
     * @param closedWorldPositions Each closed block's absolute world position at rest (index-aligned with the caller's block list)
     * @param openWorldPositions Each open-scan block's absolute world position (index-aligned with the caller's open-block list)
     * @return closed-list-index -> open-list-index for every successfully paired closed block; unpaired closed blocks have no entry
     */
    public static Map<Integer, Integer> pairNearestNeighbor(List<Vector> closedWorldPositions, List<Vector> openWorldPositions) {
        Map<Integer, Integer> pairing = new HashMap<>();
        if (closedWorldPositions == null || openWorldPositions == null || openWorldPositions.isEmpty()) {
            return pairing;
        }

        boolean[] claimed = new boolean[openWorldPositions.size()];

        for (int i = 0; i < closedWorldPositions.size(); i++) {
            Vector closedPos = closedWorldPositions.get(i);
            if (closedPos == null) {
                continue;
            }

            int bestIndex = -1;
            double bestDistanceSquared = Double.MAX_VALUE;
            for (int j = 0; j < openWorldPositions.size(); j++) {
                if (claimed[j]) {
                    continue;
                }
                Vector openPos = openWorldPositions.get(j);
                if (openPos == null) {
                    continue;
                }
                double distanceSquared = closedPos.distanceSquared(openPos);
                if (distanceSquared < bestDistanceSquared) {
                    bestDistanceSquared = distanceSquared;
                    bestIndex = j;
                }
            }

            if (bestIndex >= 0) {
                pairing.put(i, bestIndex);
                claimed[bestIndex] = true;
            }
        }

        return pairing;
    }
}

package net.knightsandkings.knk.core.gates;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for GateBlockPairing's minimum-total-distance bipartite matching (Decision 2 in
 * docs/features/gate-structure-animation/ROTATION_GAP_FILL_DESIGN.md, reworked to a true
 * optimal assignment during item 6.7's live testing on 2026-09-15 - see the class javadoc on
 * GateBlockPairing for why greedy matching, even sorted globally by distance, wasn't enough).
 */
class GateBlockPairingTest {

    @Test
    void pairNearestNeighbor_MatchesEachClosedBlockToItsClosestOpenBlock() {
        List<Vector> closed = List.of(new Vector(0, 0, 0), new Vector(10, 0, 0));
        List<Vector> open = List.of(new Vector(11, 0, 0), new Vector(1, 0, 0));

        Map<Integer, Integer> pairing = GateBlockPairing.pairNearestNeighbor(closed, open);

        assertEquals(2, pairing.size());
        assertEquals(1, pairing.get(0)); // closed[0]=(0,0,0) nearest to open[1]=(1,0,0)
        assertEquals(0, pairing.get(1)); // closed[1]=(10,0,0) nearest to open[0]=(11,0,0)
    }

    @Test
    void pairNearestNeighbor_ManyToOneTieBreak_ClosestOfTheTwoWins() {
        // Only one open block exists for two closed blocks; the optimal assignment matches it to
        // whichever closed block is actually closest (closed[0], distance 0 vs closed[1]'s
        // distance 1) - not simply whichever is processed first (Decision: many-to-one
        // tie-break, pinned down in Phase C; the "first-claimed-wins" list-order framing from the
        // original greedy implementation no longer applies since the algorithm was reworked to a
        // true optimal assignment - see GateBlockPairing's class javadoc).
        List<Vector> closed = List.of(new Vector(0, 0, 0), new Vector(0, 0, 1));
        List<Vector> open = List.of(new Vector(0, 0, 0));

        Map<Integer, Integer> pairing = GateBlockPairing.pairNearestNeighbor(closed, open);

        assertEquals(1, pairing.size());
        assertEquals(0, pairing.get(0));
        assertNull(pairing.get(1));
    }

    @Test
    void pairNearestNeighbor_ListOrderMustNotStealTheTrueNearestMatch() {
        // Regression test: the original greedy implementation matched in closed-block list order,
        // so a closed block processed first could claim an open block merely because it was that
        // closed block's own best AVAILABLE option at the time - even though a later closed block
        // is actually far closer to it. A=(5,0,0) processed first, B=(0,0,0) second; X=(0.1,0,0)
        // is almost exactly at B, not A. The optimal assignment must give B-X (the true nearest
        // pair) regardless of processing order, leaving A with the leftover Y=(20,0,0).
        List<Vector> closed = List.of(new Vector(5, 0, 0), new Vector(0, 0, 0)); // A=index0, B=index1
        List<Vector> open = List.of(new Vector(0.1, 0, 0), new Vector(20, 0, 0)); // X=index0, Y=index1

        Map<Integer, Integer> pairing = GateBlockPairing.pairNearestNeighbor(closed, open);

        assertEquals(2, pairing.size());
        assertEquals(1, pairing.get(0)); // A (index 0) gets Y (index 1) - the leftover
        assertEquals(0, pairing.get(1)); // B (index 1) gets X (index 0) - its true nearest match
    }

    @Test
    void pairNearestNeighbor_NearSymmetricCandidates_ChoosesGloballyMinimalTotalDistance() {
        // Regression test found while designing the item 6.7 live-testing fix: a purely greedy
        // matcher - even one that considers every candidate pair globally, sorted by distance,
        // and claims the single nearest edge first - can still lock in a WORSE TOTAL pairing than
        // the true minimum-weight assignment when two candidates are near-symmetric. Greedily
        // claiming the single closest edge here (closed[1]-open[0], distance squared 495025)
        // would force the other pair into distance squared 505025 (total 1,000,050) - but
        // swapping to closed[0]-open[0] + closed[1]-open[1] totals only 500000+500000=1,000,000,
        // the true optimum. Mirrors
        // GateLoaderAdapterTest#loadAndCacheStructure_RotationGateWithOpenedSnapshots_PairsByNearestWorldDistance.
        List<Vector> closed = List.of(new Vector(0, 0, 0), new Vector(5, 0, 0));
        List<Vector> open = List.of(new Vector(500, 0, 500), new Vector(505, 0, 500));

        Map<Integer, Integer> pairing = GateBlockPairing.pairNearestNeighbor(closed, open);

        assertEquals(2, pairing.size());
        assertEquals(0, pairing.get(0)); // closed[0] pairs with open[0] - the globally optimal total, not the single nearest edge
        assertEquals(1, pairing.get(1));
    }

    @Test
    void pairNearestNeighbor_FewerOpenBlocksThanClosed_LeavesExcessClosedBlocksUnpaired() {
        List<Vector> closed = List.of(new Vector(0, 0, 0), new Vector(5, 0, 0), new Vector(10, 0, 0));
        List<Vector> open = List.of(new Vector(0, 0, 0));

        Map<Integer, Integer> pairing = GateBlockPairing.pairNearestNeighbor(closed, open);

        assertEquals(1, pairing.size());
        assertEquals(0, pairing.get(0));
    }

    @Test
    void pairNearestNeighbor_EmptyOpenList_ReturnsEmptyPairing() {
        List<Vector> closed = List.of(new Vector(0, 0, 0));
        assertTrue(GateBlockPairing.pairNearestNeighbor(closed, List.of()).isEmpty());
    }

    @Test
    void pairNearestNeighbor_NullInputs_ReturnsEmptyPairing() {
        assertTrue(GateBlockPairing.pairNearestNeighbor(null, List.of(new Vector(0, 0, 0))).isEmpty());
        assertTrue(GateBlockPairing.pairNearestNeighbor(List.of(new Vector(0, 0, 0)), null).isEmpty());
    }
}

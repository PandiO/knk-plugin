package net.knightsandkings.knk.core.gates;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for GateBlockPairing's greedy nearest-neighbor algorithm (Decision 2 in
 * docs/features/gate-structure-animation/ROTATION_GAP_FILL_DESIGN.md).
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
    void pairNearestNeighbor_ManyToOneTieBreak_FirstClaimedWins() {
        // Both closed blocks are equidistant-ish from the single open block, but closed[0] is
        // processed first (list order) and claims it; closed[1] is left unpaired since no other
        // open block exists - Decision (many-to-one tie-break) pinned down in Phase C.
        List<Vector> closed = List.of(new Vector(0, 0, 0), new Vector(0, 0, 1));
        List<Vector> open = List.of(new Vector(0, 0, 0));

        Map<Integer, Integer> pairing = GateBlockPairing.pairNearestNeighbor(closed, open);

        assertEquals(1, pairing.size());
        assertEquals(0, pairing.get(0));
        assertNull(pairing.get(1));
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

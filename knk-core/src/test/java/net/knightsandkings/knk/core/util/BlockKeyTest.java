package net.knightsandkings.knk.core.util;

import net.knightsandkings.knk.core.gates.GateSpatialIndex;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Unit tests for BlockKey: pack/unpack round-trips over the whole world height (incl. the
 * negative y of 1.18+ worlds), negative x/z, the 26/12/26 layout shared with
 * GateSpatialIndex.packCell, and neighbour keys.
 */
class BlockKeyTest {

    @Test
    void packUnpackRoundTripsPositiveCoordinates() {
        long key = BlockKey.pack(100, 64, 200);

        assertEquals(100, BlockKey.x(key));
        assertEquals(64, BlockKey.y(key));
        assertEquals(200, BlockKey.z(key));
    }

    @Test
    void packUnpackRoundTripsTheWorldHeightExtremes() {
        long bottom = BlockKey.pack(0, -64, 0);
        long top = BlockKey.pack(0, 319, 0);

        assertEquals(-64, BlockKey.y(bottom));
        assertEquals(319, BlockKey.y(top));
        assertNotEquals(bottom, top);
    }

    @Test
    void packUnpackRoundTripsNegativeXAndZ() {
        long key = BlockKey.pack(-1_500_000, 12, -7);

        assertEquals(-1_500_000, BlockKey.x(key));
        assertEquals(12, BlockKey.y(key));
        assertEquals(-7, BlockKey.z(key));
    }

    @Test
    void packUnpackRoundTripsTheFullTwelveBitYRange() {
        for (int y = -2048; y <= 2047; y++) {
            assertEquals(y, BlockKey.y(BlockKey.pack(3, y, -3)), "y=" + y);
        }
    }

    @Test
    void packUnpackRoundTripsTheWorldBorderExtremesOfXAndZ() {
        int[] extremes = {-30_000_000, -1, 0, 1, 30_000_000};
        for (int x : extremes) {
            for (int z : extremes) {
                long key = BlockKey.pack(x, -64, z);
                assertEquals(x, BlockKey.x(key), "x for (" + x + "," + z + ")");
                assertEquals(z, BlockKey.z(key), "z for (" + x + "," + z + ")");
                assertEquals(-64, BlockKey.y(key));
            }
        }
    }

    @Test
    void distinctBlocksGetDistinctKeys() {
        Set<Long> keys = new HashSet<>();
        for (int x = -2; x <= 2; x++) {
            for (int y = -65; y <= -62; y++) {
                for (int z = -2; z <= 2; z++) {
                    keys.add(BlockKey.pack(x, y, z));
                }
            }
        }
        assertEquals(5 * 4 * 5, keys.size());
    }

    @Test
    void layoutMatchesGateSpatialIndexPackCell() {
        // The gate index and the road builder must agree on the key of a block (D9: gate cells
        // are tagged by closed footprint), so the two packers stay bit-identical.
        assertEquals(GateSpatialIndex.packCell(100, 64, 100), BlockKey.pack(100, 64, 100));
        assertEquals(GateSpatialIndex.packCell(-5, -64, 7), BlockKey.pack(-5, -64, 7));
        assertEquals(GateSpatialIndex.packCell(123456, 319, -654321), BlockKey.pack(123456, 319, -654321));
    }

    @Test
    void neighbourOffsetsEachComponent() {
        long key = BlockKey.pack(10, 64, -10);

        assertEquals(BlockKey.pack(11, 64, -10), BlockKey.neighbour(key, 1, 0, 0));
        assertEquals(BlockKey.pack(10, 63, -10), BlockKey.neighbour(key, 0, -1, 0));
        assertEquals(BlockKey.pack(10, 64, -11), BlockKey.neighbour(key, 0, 0, -1));
        assertEquals(BlockKey.pack(9, 66, -8), BlockKey.neighbour(key, -1, 2, 2));
    }

    @Test
    void neighbourCrossesZeroAndTheNegativeYBoundary() {
        long origin = BlockKey.pack(0, 0, 0);
        long below = BlockKey.neighbour(origin, -1, -1, -1);

        assertEquals(-1, BlockKey.x(below));
        assertEquals(-1, BlockKey.y(below));
        assertEquals(-1, BlockKey.z(below));
        assertEquals(origin, BlockKey.neighbour(below, 1, 1, 1));
    }
}

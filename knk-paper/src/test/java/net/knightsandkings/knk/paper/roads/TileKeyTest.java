package net.knightsandkings.knk.paper.roads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TileKeyTest {

    @Test
    void tileMathsUsesFloorDivision() {
        assertEquals(new TileKey("w", 0, 0), TileKey.of("w", 0, 511));
        assertEquals(new TileKey("w", 1, -1), TileKey.of("w", 512, -1));
        assertEquals(new TileKey("w", -1, -2), TileKey.of("w", -512, -513));
        TileKey key = new TileKey("w", -1, 2);
        assertEquals(-512, key.minX());
        assertEquals(-1, key.maxX());
        assertEquals(1024, key.minZ());
        assertEquals(1535, key.maxZ());
        assertTrue(key.contains(-1, 1535));
        assertFalse(key.contains(0, 1535));
        assertEquals("-1_2", key.fileName());
    }

    @Test
    void distanceIsChebyshevWithinAWorld() {
        assertEquals(3, new TileKey("w", 0, 0).distanceTo(new TileKey("w", 3, -2)));
        assertEquals(Integer.MAX_VALUE, new TileKey("w", 0, 0).distanceTo(new TileKey("nether", 0, 0)));
    }
}

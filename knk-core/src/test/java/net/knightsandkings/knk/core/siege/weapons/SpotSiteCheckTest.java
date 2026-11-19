package net.knightsandkings.knk.core.siege.weapons;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** § 4.6 (W44): warn about a floor that isn't solid and a volume that isn't clear. */
class SpotSiteCheckTest {

    private static final SpotBounds SPOT = new SpotBounds(0, 64, 0, 6, 68, 8);

    @Test
    void solidFloorAndAirAbove_HasNoWarnings() {
        SpotSiteCheck.Result result = SpotSiteCheck.check(SPOT, probe(-1, -1));
        assertFalse(result.hasWarnings());
        assertTrue(result.warnings().isEmpty());
        assertEquals(63, result.floorBlocks());
    }

    @Test
    void aFloorPartlyAir_Warns() {
        // Every block with x == 0 under the spot is air: 9 of 63.
        SpotSiteCheck.Result result = SpotSiteCheck.check(SPOT, probe(0, -1));
        assertTrue(result.hasWarnings());
        assertEquals(9, result.openFloor());
        assertTrue(result.warnings().getFirst().startsWith("The floor isn't solid: 9 of 63"), result.warnings().toString());
    }

    @Test
    void blocksInsideTheSpot_Warn() {
        // The column x == 3 inside the spot is stone: 5 high × 9 = 45 blocks.
        SpotSiteCheck.Result result = SpotSiteCheck.check(SPOT, probe(-1, 3));
        assertEquals(45, result.blockedVolume());
        assertTrue(result.warnings().getFirst().startsWith("The volume isn't clear: 45 blocks"), result.warnings().toString());
    }

    @Test
    void aHugeSelection_ChecksTheFloorButNotTheVolume() {
        SpotBounds huge = new SpotBounds(0, 0, 0, 99, 99, 99);
        SpotSiteCheck.Result result = SpotSiteCheck.check(huge, probe(-1, 3));
        assertFalse(result.volumeChecked());
        assertEquals(10_000, result.floorBlocks());
        assertTrue(result.warnings().getLast().contains("wasn't checked"), result.warnings().toString());
    }

    /** Floor at y 63 solid except x == airFloorX; inside the spot clear except x == stoneX. */
    private static SpotSiteCheck.BlockProbe probe(int airFloorX, int stoneX) {
        return new SpotSiteCheck.BlockProbe() {
            @Override
            public boolean isSolid(int x, int y, int z) {
                return y == 63 && x != airFloorX;
            }

            @Override
            public boolean isClear(int x, int y, int z) {
                return x != stoneX;
            }
        };
    }
}

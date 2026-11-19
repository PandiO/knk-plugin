package net.knightsandkings.knk.core.siege.weapons;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** W70, § 4.3, A02-A03: the size class from width across × length along the facing × height. */
class SpotSizingTest {

    /** 7 blocks along x (east-west), 5 high, 9 along z (north-south). */
    private static final SpotBounds SEVEN_BY_NINE = new SpotBounds(0, 64, 0, 6, 68, 8);

    @Test
    void sevenWideNineDeep_FacingNorth_IsSmall() {
        assertEquals(Optional.of(SpotSizeClass.SMALL), SpotSizing.classFor(SEVEN_BY_NINE, SpotFacing.NORTH));
        assertEquals(Optional.of(SpotSizeClass.SMALL), SpotSizing.classFor(SEVEN_BY_NINE, SpotFacing.SOUTH));
    }

    @Test
    void theSameAreaFacingEast_IsTooShortAlongTheFacing_AndRefused() {
        assertTrue(SpotSizing.classFor(SEVEN_BY_NINE, SpotFacing.EAST).isEmpty());
        String refusal = SpotSizing.refusal(SEVEN_BY_NINE, SpotFacing.EAST).orElseThrow();
        assertTrue(refusal.contains("9 wide × 7 long × 5 high"), refusal);
        assertTrue(refusal.contains("7 wide × 9 long × 5 high"), refusal);
        assertTrue(refusal.contains("fits facing north or south"), refusal);
        assertEquals(List.of(SpotFacing.NORTH, SpotFacing.SOUTH), SpotSizing.facingsThatFit(SEVEN_BY_NINE));
    }

    @Test
    void theLargestClassThatFitsIsChosen() {
        assertEquals(Optional.of(SpotSizeClass.MEDIUM),
                SpotSizing.classFor(new SpotBounds(0, 64, 0, 6, 71, 8), SpotFacing.NORTH));
        assertEquals(Optional.of(SpotSizeClass.LARGE),
                SpotSizing.classFor(new SpotBounds(0, 64, 0, 8, 79, 14), SpotFacing.NORTH));
        // Wider and longer than large but only 8 high: medium.
        assertEquals(Optional.of(SpotSizeClass.MEDIUM),
                SpotSizing.classFor(new SpotBounds(0, 64, 0, 20, 71, 20), SpotFacing.WEST));
    }

    @Test
    void aLargeFootprintTurnedSideways_DropsToTheClassThatStillFits() {
        // 9 along x, 17 high, 15 along z: large facing north, but facing east it is 15 wide × 9 long.
        SpotBounds bounds = new SpotBounds(0, 64, 0, 8, 80, 14);
        assertEquals(Optional.of(SpotSizeClass.LARGE), SpotSizing.classFor(bounds, SpotFacing.NORTH));
        assertEquals(Optional.of(SpotSizeClass.MEDIUM), SpotSizing.classFor(bounds, SpotFacing.EAST));
    }

    @Test
    void smallerThanTheSmallClass_IsRefusedInEveryFacing() {
        SpotBounds fiveCube = new SpotBounds(0, 64, 0, 4, 68, 4);
        for (SpotFacing facing : SpotFacing.values()) {
            assertTrue(SpotSizing.classFor(fiveCube, facing).isEmpty(), facing.name());
        }
        String refusal = SpotSizing.refusal(fiveCube, SpotFacing.NORTH).orElseThrow();
        assertTrue(refusal.startsWith("Too small for a weapon facing north"), refusal);
        assertTrue(!refusal.contains("It fits facing"), refusal);
    }

    @Test
    void tooLow_IsRefused() {
        assertTrue(SpotSizing.classFor(new SpotBounds(0, 64, 0, 6, 67, 8), SpotFacing.NORTH).isEmpty());
    }

    @Test
    void bounds_SortTheirCorners() {
        SpotBounds bounds = new SpotBounds(6, 68, 8, 0, 64, 0);
        assertEquals(SEVEN_BY_NINE, bounds);
        assertEquals(7, bounds.widthAcross(SpotFacing.NORTH));
        assertEquals(9, bounds.lengthAlong(SpotFacing.NORTH));
        assertEquals(9, bounds.widthAcross(SpotFacing.WEST));
        assertEquals(7 * 5 * 9, bounds.volume());
    }
}

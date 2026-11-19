package net.knightsandkings.knk.core.siege.weapons;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** W5 / § 4.4: the facing is the look direction rounded to north, east, south or west. */
class SpotFacingTest {

    @Test
    void fromYaw_RoundsToTheNearestCompassDirection() {
        assertEquals(SpotFacing.SOUTH, SpotFacing.fromYaw(0f));
        assertEquals(SpotFacing.WEST, SpotFacing.fromYaw(90f));
        assertEquals(SpotFacing.NORTH, SpotFacing.fromYaw(180f));
        assertEquals(SpotFacing.NORTH, SpotFacing.fromYaw(-180f));
        assertEquals(SpotFacing.EAST, SpotFacing.fromYaw(-90f));
        assertEquals(SpotFacing.EAST, SpotFacing.fromYaw(270f));
        assertEquals(SpotFacing.SOUTH, SpotFacing.fromYaw(44.9f));
        assertEquals(SpotFacing.WEST, SpotFacing.fromYaw(45f));
        assertEquals(SpotFacing.NORTH, SpotFacing.fromYaw(160f));
        assertEquals(SpotFacing.SOUTH, SpotFacing.fromYaw(359f));
        assertEquals(SpotFacing.SOUTH, SpotFacing.fromYaw(-30f));
        assertEquals(SpotFacing.NORTH, SpotFacing.fromYaw(540f));
    }

    @Test
    void parse_ReadsNamesAndInitials_AnyCase() {
        assertEquals(Optional.of(SpotFacing.EAST), SpotFacing.parse("East"));
        assertEquals(Optional.of(SpotFacing.WEST), SpotFacing.parse(" w "));
        assertEquals(Optional.of(SpotFacing.NORTH), SpotFacing.parse("NORTH"));
        assertTrue(SpotFacing.parse("up").isEmpty());
        assertTrue(SpotFacing.parse(null).isEmpty());
    }

    @Test
    void northAndSouth_PointAlongZ() {
        assertTrue(SpotFacing.NORTH.alongZ());
        assertTrue(SpotFacing.SOUTH.alongZ());
        assertFalse(SpotFacing.EAST.alongZ());
        assertEquals("West", SpotFacing.WEST.apiName());
    }
}

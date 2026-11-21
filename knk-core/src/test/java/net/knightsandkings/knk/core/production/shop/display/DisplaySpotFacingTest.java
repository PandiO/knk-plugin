package net.knightsandkings.knk.core.production.shop.display;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Display stalls P12 (PandiO/knk-workspace#250): the item turns towards the admin who registered the spot. */
class DisplaySpotFacingTest {

    @Test
    void theItemFacesThePlayerWhoLooksAtIt() {
        assertEquals(DisplaySpot.Facing.NORTH, DisplaySpot.Facing.towards(0f));     // looking south → item faces north
        assertEquals(DisplaySpot.Facing.EAST, DisplaySpot.Facing.towards(90f));     // looking west
        assertEquals(DisplaySpot.Facing.SOUTH, DisplaySpot.Facing.towards(180f));   // looking north
        assertEquals(DisplaySpot.Facing.WEST, DisplaySpot.Facing.towards(-90f));    // looking east
        assertEquals(DisplaySpot.Facing.NORTH, DisplaySpot.Facing.towards(350f));
        assertEquals(DisplaySpot.Facing.SOUTH, DisplaySpot.Facing.towards(-180f));
    }

    @Test
    void parsesTheApisNames_andDefaultsToSouth() {
        assertEquals(DisplaySpot.Facing.WEST, DisplaySpot.Facing.parse("West"));
        assertEquals(DisplaySpot.Facing.EAST, DisplaySpot.Facing.parse(" east "));
        assertEquals(DisplaySpot.Facing.SOUTH, DisplaySpot.Facing.parse("Up"));
        assertEquals(DisplaySpot.Facing.SOUTH, DisplaySpot.Facing.parse(null));
        assertEquals("North", DisplaySpot.Facing.NORTH.apiName());
        assertEquals(180f, DisplaySpot.Facing.NORTH.yaw());
    }

    @Test
    void describesASpotForStaff() {
        DisplaySpot spot = new DisplaySpot(3, 5, "world", 10, 64, -2, null, null, 1, "Front shelf", true);
        assertEquals("#3 \"Front shelf\" (world 10 64 -2)", spot.describe());
        assertEquals(0, spot.chunkX());
        assertEquals(-1, spot.chunkZ());
    }
}

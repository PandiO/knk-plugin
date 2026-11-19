package net.knightsandkings.knk.core.siege.weapons;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** #799: the world task result the web form spreads over the spot's fields (A02), never for a refused spot (A03). */
class SpotTaskOutputTest {

    private static final SpotBounds SEVEN_BY_NINE = new SpotBounds(0, 64, 0, 6, 68, 8);
    private static final SpotSiteCheck.Result CLEAR = new SpotSiteCheck.Result(63, 0, true, 0);
    private static final float LOOKING_NORTH = 180f;

    @Test
    void aClearSpot_CarriesBoundsFacingSizeClassAndNoWarnings() {
        SpotDraft draft = SpotDraft.capture(SEVEN_BY_NINE, null, LOOKING_NORTH, null, CLEAR);
        JsonObject json = JsonParser.parseString(SpotTaskOutput.toJson(draft, "siege", 1234L).orElseThrow()).getAsJsonObject();

        assertEquals("SiegeWeaponSpot", json.get("fieldName").getAsString());
        assertEquals("Success", json.get("status").getAsString());
        assertEquals(0, json.get("minX").getAsInt());
        assertEquals(64, json.get("minY").getAsInt());
        assertEquals(0, json.get("minZ").getAsInt());
        assertEquals(6, json.get("maxX").getAsInt());
        assertEquals(68, json.get("maxY").getAsInt());
        assertEquals(8, json.get("maxZ").getAsInt());
        assertEquals("North", json.get("facing").getAsString());
        assertEquals("Small", json.get("sizeClass").getAsString());
        assertTrue(json.get("wgRegionId").isJsonNull());
        assertEquals("siege", json.get("world").getAsString());
        assertEquals(0, json.getAsJsonArray("warnings").size());
        assertEquals(1234L, json.get("capturedAt").getAsLong());
    }

    @Test
    void aFacingOverride_ChangesTheSizeClassInTheResult_AndARegionIdAndWarningsAreKept() {
        // 15 along x, 9 along z, 16 high: Large facing east (9 across x 15 along), Medium facing north (15 across, 9 along).
        SpotBounds wide = new SpotBounds(0, 64, 0, 14, 79, 8);
        SpotSiteCheck.Result openFloor = new SpotSiteCheck.Result(135, 3, true, 0);
        SpotDraft north = SpotDraft.capture(wide, "spot_tower", LOOKING_NORTH, null, openFloor);
        JsonObject northJson = JsonParser.parseString(SpotTaskOutput.toJson(north, "siege", 1L).orElseThrow()).getAsJsonObject();
        assertEquals("Medium", northJson.get("sizeClass").getAsString());

        JsonObject east = JsonParser.parseString(
                SpotTaskOutput.toJson(north.withFacing(SpotFacing.EAST), "siege", 1L).orElseThrow()).getAsJsonObject();
        assertEquals("East", east.get("facing").getAsString());
        assertEquals("Large", east.get("sizeClass").getAsString());
        assertEquals("spot_tower", east.get("wgRegionId").getAsString());
        assertEquals("Warning", east.get("status").getAsString());
        assertTrue(east.getAsJsonArray("warnings").get(0).getAsString().startsWith("The floor isn't solid: 3 of 135"));
    }

    @Test
    void aRefusedSpot_HasNoResult() {
        SpotDraft east = SpotDraft.capture(SEVEN_BY_NINE, null, LOOKING_NORTH, SpotFacing.EAST, CLEAR);
        assertTrue(SpotTaskOutput.toJson(east, "siege", 1L).isEmpty());
        SpotDraft tiny = SpotDraft.capture(new SpotBounds(0, 64, 0, 4, 68, 4), null, LOOKING_NORTH, null, CLEAR);
        assertTrue(SpotTaskOutput.toJson(tiny, "siege", 1L).isEmpty());
    }
}

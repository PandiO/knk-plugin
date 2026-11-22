package net.knightsandkings.knk.paper.tasks;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion;

/**
 * PandiO/knk-workspace#951 (plugin part, option 2): the region task's result carries the drawn (or selected) region's
 * block bounding box, so the web form can fill a production area's empty bounds at the first save.
 */
class WgRegionIdTaskOutputTest {

    @Test
    void theResultCarriesTheRegionsBoundingBox() {
        JsonObject out = JsonParser.parseString(WgRegionIdTaskHandler.outputJson("tempregion_worldtask_7", "world", "town_1",
            1234L, new int[] {-5, 60, 2, 9, 72, 30})).getAsJsonObject();

        assertEquals("WgRegionId", out.get("fieldName").getAsString());
        assertEquals("tempregion_worldtask_7", out.get("regionId").getAsString());
        assertEquals("world", out.get("worldName").getAsString());
        assertEquals("town_1", out.get("parentRegionId").getAsString());
        assertEquals(1234L, out.get("createdAt").getAsLong());
        JsonObject bounds = out.getAsJsonObject("bounds");
        assertEquals(-5, bounds.get("minX").getAsInt());
        assertEquals(60, bounds.get("minY").getAsInt());
        assertEquals(2, bounds.get("minZ").getAsInt());
        assertEquals(9, bounds.get("maxX").getAsInt());
        assertEquals(72, bounds.get("maxY").getAsInt());
        assertEquals(30, bounds.get("maxZ").getAsInt());
    }

    @Test
    void withoutBoundsOrParentThoseAreLeftOut() {
        JsonObject out = JsonParser.parseString(WgRegionIdTaskHandler.outputJson("r", "world", null, 1L, null))
            .getAsJsonObject();
        assertFalse(out.has("bounds"));
        assertFalse(out.has("parentRegionId"));
    }

    @Test
    void theBoxOfACuboidAndOfAPolygon() {
        assertArrayEquals(new int[] {1, 2, 3, 4, 5, 6}, WgRegionIdTaskHandler.bounds(
            new ProtectedCuboidRegion("c", BlockVector3.at(4, 5, 6), BlockVector3.at(1, 2, 3))));
        assertArrayEquals(new int[] {0, 10, -4, 12, 20, 8}, WgRegionIdTaskHandler.bounds(new ProtectedPolygonalRegion("p",
            List.of(BlockVector2.at(0, 0), BlockVector2.at(12, -4), BlockVector2.at(6, 8)), 10, 20)));
        assertNull(WgRegionIdTaskHandler.bounds(null));
    }
}

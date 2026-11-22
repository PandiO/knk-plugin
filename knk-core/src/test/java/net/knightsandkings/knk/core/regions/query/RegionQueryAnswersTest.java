package net.knightsandkings.knk.core.regions.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.siege.weapons.SpotBounds;
import net.knightsandkings.knk.core.siege.weapons.SpotFacing;
import net.knightsandkings.knk.core.siege.weapons.SpotSiteCheck;
import net.knightsandkings.knk.core.siege.weapons.SpotSizeClass;
import net.knightsandkings.knk.core.siege.weapons.SpotSizing;

/**
 * PandiO/knk-workspace#988: the bodies of the hub answers RegionGeometryResult and SpotSiteCheckResult, as the API reads
 * them (RegionGeometryResultDto / SpotSiteCheckResultDto), and the region shape as the spot classes read it.
 */
class RegionQueryAnswersTest {

    @Test
    void geometry_ACuboid() {
        RegionShape shape = new RegionShape("siege", "Cuboid", 0, 64, 0, 8, 80, 14, null);
        Map<String, Object> body = RegionQueryAnswers.geometry("q1", shape);
        assertEquals("q1", body.get("queryId"));
        assertEquals("found", body.get("result"));
        assertEquals("siege", body.get("world"));
        assertEquals("cuboid", body.get("shape"));
        assertEquals(List.of(0, 64, 0, 8, 80, 14), List.of(body.get("minX"), body.get("minY"), body.get("minZ"),
                body.get("maxX"), body.get("maxY"), body.get("maxZ")));
        assertEquals(List.of(), body.get("points"));
    }

    @Test
    void geometry_APolygon_SendsItsPoints() {
        RegionShape shape = new RegionShape("siege", "polygon", -2, 60, 0, 10, 70, 9,
                List.of(new int[] {-2, 0}, new int[] {10, 0}, new int[] {4, 9}));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> points = (List<Map<String, Object>>) RegionQueryAnswers.geometry("q", shape).get("points");
        assertEquals(3, points.size());
        assertEquals(Map.of("x", 4, "z", 9), points.get(2));
    }

    @Test
    void theShapesBounds_GiveTheSpotClass() {
        RegionShape shape = new RegionShape("siege", "cuboid", 0, 64, 0, 8, 80, 14, List.of());
        SpotBounds bounds = shape.bounds();
        assertEquals(SpotSizeClass.LARGE, SpotSizing.classFor(bounds, SpotFacing.NORTH).orElseThrow());
        assertEquals(SpotSizeClass.MEDIUM, SpotSizing.classFor(bounds, SpotFacing.EAST).orElseThrow());
    }

    @Test
    void siteCheck_CarriesTheCountsAndWarnings() {
        SpotSiteCheck.Result result = new SpotSiteCheck.Result(63, 4, true, 12);
        Map<String, Object> body = RegionQueryAnswers.siteCheck("q2", result);
        assertEquals("found", body.get("result"));
        assertEquals(63, body.get("floorBlocks"));
        assertEquals(4, body.get("openFloor"));
        assertEquals(true, body.get("volumeChecked"));
        assertEquals(12L, body.get("blockedVolume"));
        assertEquals(result.warnings(), body.get("warnings"));
        assertEquals("The site has warnings.", body.get("message"));
        assertEquals("The floor is solid and the space is clear.",
                RegionQueryAnswers.siteCheck("q3", new SpotSiteCheck.Result(63, 0, true, 0)).get("message"));
    }

    @Test
    void notFoundAndError() {
        Map<String, Object> notFound = RegionQueryAnswers.notFound("q", "nowhere", "siege");
        assertEquals("not-found", notFound.get("result"));
        assertEquals("No WorldGuard region 'nowhere' in siege.", notFound.get("message"));
        assertTrue(((String) RegionQueryAnswers.notFound("q", "nowhere", null).get("message")).endsWith("in any loaded world."));
        assertEquals("error", RegionQueryAnswers.error("q", "boom").get("result"));
    }
}

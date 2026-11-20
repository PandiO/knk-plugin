package net.knightsandkings.knk.core.production.survey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.production.survey.SurveyProfile.UnitType;

/** Area shapes, the API's area specs, survey profiles, settings, outlines, unit keys and the notable-change tracker
 * (PandiO/knk-workspace#679). */
class SurveyAreasTest {

    private static AreaSpec spec(String shape, String region, List<int[]> points, Integer cx, Integer cz, Integer radius,
                                 Integer minX, Integer minY, Integer minZ, Integer maxX, Integer maxY, Integer maxZ) {
        return new AreaSpec(1, 2, "Logger", "Forest", shape, false, true, "world", region, points, cx, 64, cz, radius,
                minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Test
    void radiusIsAVerticalCylinderOfColumnCentres() {
        AreaShape r = new AreaShape.Radius(0, 0, 2, 60, 70);
        assertTrue(r.contains(2, 60, 0));
        assertTrue(r.contains(1, 70, 1));
        assertFalse(r.contains(2, 65, 1));
        assertFalse(r.contains(0, 71, 0));
        assertEquals(13, r.columnCount());
        assertEquals(13 * 11, r.blockCount());
    }

    @Test
    void polygonFollowsWorldGuardEdgesInside() {
        AreaShape p = AreaShape.Polygon.of(List.of(new int[] {0, 0}, new int[] {4, 0}, new int[] {4, 4}, new int[] {0, 4}), 0, 10);
        assertTrue(p.contains(0, 5, 0));
        assertTrue(p.contains(4, 5, 4));
        assertTrue(p.contains(2, 5, 4));
        assertFalse(p.contains(5, 5, 2));
        assertEquals(25, p.columnCount());
        assertThrows(IllegalArgumentException.class, () -> AreaShape.Polygon.of(List.of(new int[] {0, 0}), 0, 1));
    }

    @Test
    void specsResolveToShapesClampedToTheWorld() {
        AreaShape cuboid = spec("Cuboid", null, null, null, null, null, 5, null, 5, 0, 400, 0).resolve(null, -64, 320);
        assertEquals(new AreaShape.Cuboid(0, -64, 0, 5, 319, 5), cuboid);
        AreaShape radius = spec("radius", null, null, 10, 20, 8, null, null, null, null, null, null).resolve(null, -64, 320);
        assertEquals(new AreaShape.Radius(10, 20, 8, -64, 319), radius);
        AreaShape polygon = spec("Polygon", null, List.of(new int[] {0, 0}, new int[] {9, 0}, new int[] {0, 9}), null, null,
                null, null, 60, null, null, 80, null).resolve(null, -64, 320);
        assertEquals(60, polygon.minY());
        assertEquals(80, polygon.maxY());
        AreaShape region = spec("Region", "quarry_1", null, null, null, null, null, null, null, null, null, null)
                .resolve((world, id) -> "quarry_1".equals(id) ? Optional.of(new AreaShape.Cuboid(0, 0, 0, 3, 3, 3))
                        : Optional.empty(), -64, 320);
        assertEquals(new AreaShape.Cuboid(0, 0, 0, 3, 3, 3), region);
    }

    @Test
    void brokenSpecsSayWhy() {
        assertEquals("WorldGuard region gone not found in world", assertThrows(IllegalArgumentException.class,
                () -> spec("Region", "gone", null, null, null, null, null, null, null, null, null, null)
                        .resolve(AreaSpec.RegionShapes.NONE, 0, 256)).getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> spec("Radius", null, null, null, null, 4, null, null, null, null, null, null).resolve(null, 0, 256));
        assertThrows(IllegalArgumentException.class,
                () -> spec("Blob", null, null, null, null, null, null, null, null, null, null, null).resolve(null, 0, 256));
    }

    @Test
    void profilesReadTheSeededFormAndThePluginsExtensions() {
        SurveyProfile seeded = SurveyProfile.parse("{\"unitTypes\":[\"TREE\",\"SAPLING\"]}", 1);
        assertEquals(EnumSet.of(UnitType.TREE, UnitType.SAPLING), seeded.unitTypes());
        assertEquals(1, seeded.version());
        assertEquals(SurveyProfile.DEFAULT_MEDIUM_MAX_LOGS, seeded.mediumMaxLogs());
        SurveyProfile extended = SurveyProfile.parse("{\"unitTypes\":[\"ENTITY\",\"HUNT\"],\"entityTypes\":[\"sheep\"],"
                + "\"tree\":{\"smallMaxLogs\":4,\"mediumMaxLogs\":10,\"maxLogs\":100,\"leafReach\":2}}", 3);
        assertEquals(EnumSet.of(UnitType.ENTITY), extended.unitTypes());
        assertEquals(List.of("unknown unit type HUNT ignored"), extended.warnings());
        assertEquals(java.util.Set.of("SHEEP"), extended.entityTypes());
        assertEquals(4, extended.smallMaxLogs());
        assertEquals(2, extended.leafReach());
        SurveyProfile broken = SurveyProfile.parse("{nope", 1);
        assertTrue(broken.unitTypes().isEmpty());
        assertTrue(broken.warnings().get(0).startsWith("survey profile is not JSON"));
        assertTrue(SurveyProfile.parse(null, 1).unitTypes().isEmpty());
    }

    @Test
    void settingsRefuseAreasOverTheCaps() {
        SurveySettings settings = new SurveySettings(true, 5, 20, 2, 1000, 4, false, 64, 30, 10);
        assertNull(settings.refusal(new AreaShape.Cuboid(0, 0, 0, 9, 9, 9)));
        assertTrue(settings.refusal(new AreaShape.Cuboid(0, 0, 0, 10, 9, 9)).contains("max-area-blocks"));
        assertTrue(settings.refusal(new AreaShape.Cuboid(0, 0, 0, 80, 0, 0)).contains("max-area-chunks"));
    }

    @Test
    void outlinesStayUnderThePointLimit() {
        assertEquals(40, AreaOutline.points(new AreaShape.Cuboid(0, 0, 0, 9, 0, 9), 1, 1000).size());
        assertTrue(AreaOutline.points(new AreaShape.Radius(0, 0, 500, 0, 0), 1, 200).size() <= 200);
        assertEquals(69, AreaOutline.points(AreaShape.Polygon.of(List.of(new int[] {0, 0}, new int[] {10, 0},
                new int[] {10, 10}), 0, 0), 0.5, 1000).size());
    }

    @Test
    void unitKeysFollowTheApisRules() {
        assertEquals("TREE:DARK_OAK:LARGE", SurveyUnit.key("tree", "dark oak", "LARGE"));
        assertEquals("WATER:UNKNOWN", SurveyUnit.key("WATER", null));
        assertTrue(SurveyUnit.isValidKey(SurveyUnit.key("BLOCK", "minecraft:stone")));
        assertEquals(100, SurveyUnit.key("BLOCK", "X".repeat(200)).length());
    }

    @Test
    void notableChangesFireAtTheThresholdOncePerCooldown() {
        NotableChangeTracker tracker = new NotableChangeTracker(3, 60_000);
        tracker.setAreas(List.of(new NotableChangeTracker.TrackedArea(5, 2, "world", new AreaShape.Cuboid(0, 0, 0, 20, 100, 20)),
                new NotableChangeTracker.TrackedArea(6, 3, "world", new AreaShape.Radius(1000, 1000, 5, 0, 100))), 100);
        assertTrue(tracker.record("world", 1, 64, 1, 0).isEmpty());
        assertTrue(tracker.record("world", 50, 64, 50, 0).isEmpty()); // outside every area
        assertTrue(tracker.record("other", 1, 64, 1, 0).isEmpty());
        assertTrue(tracker.record("world", 2, 64, 2, 0).isEmpty());
        assertEquals(List.of(5), tracker.record("world", 3, 64, 3, 0).stream().map(a -> a.areaId()).toList());
        assertEquals(0, tracker.changesOf(5));
        for (int i = 0; i < 3; i++) {
            assertTrue(tracker.record("world", 4, 64, 4, 1000).isEmpty()); // within the cooldown
        }
        assertEquals(List.of(5), tracker.record("world", 4, 64, 4, 61_000).stream().map(a -> a.areaId()).toList());
        tracker.record("world", 4, 64, 4, 70_000);
        tracker.surveyed(5);
        assertEquals(0, tracker.changesOf(5));
        assertTrue(new NotableChangeTracker(0, 0).record("world", 1, 1, 1, 0).isEmpty());
    }
}

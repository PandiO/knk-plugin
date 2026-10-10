package net.knightsandkings.knk.core.domain.roads;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.roads.survey.ProposedProfile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Phase 2e port records (API shapes without a knk-core twin) and the {@code locked} field on RoadNode. */
class RoadApiRecordsTest {

    @Test
    void seedSourceApiNamesRoundTrip() {
        assertEquals("Admin", RoadSeedSource.ADMIN.apiName());
        assertEquals("Survey", RoadSeedSource.SURVEY.apiName());
        for (RoadSeedSource source : RoadSeedSource.values()) {
            assertEquals(source, RoadSeedSource.fromApiName(source.apiName()));
            assertEquals(source, RoadSeedSource.fromApiName(" " + source.apiName().toLowerCase() + " "));
        }
    }

    @Test
    void roadNodeKeepsTheSevenArgConstructorUnlocked() {
        RoadNode node = new RoadNode(3, 1, 64, 2, RoadNodeKind.JUNCTION, null, 3);
        assertFalse(node.locked());
        assertTrue(new RoadNode(3, 1, 64, 2, RoadNodeKind.ANCHOR, "Gate", 3, true).locked());
        assertEquals(node, new RoadNode(3, 1, 64, 2, RoadNodeKind.JUNCTION, "  ", 3, false));
    }

    @Test
    void tileEtagIsTheQuotedVersionAndTileCoordinateFloors() {
        RoadTile tile = new RoadTile(7, "world", 0, -1, 3, OffsetDateTime.parse("2026-09-27T10:00:00Z"), 1, false,
            10, 3, 2, 1, List.of());
        assertEquals("\"3\"", tile.etag());
        assertTrue(tile.isBuilt());
        assertFalse(new RoadTile(8, "world", 1, 1, 1, null, 0, true, 0, 0, 0, 0, List.of("x")).isBuilt());
        assertEquals(0, RoadTile.tileCoordinate(511));
        assertEquals(1, RoadTile.tileCoordinate(512));
        assertEquals(-1, RoadTile.tileCoordinate(-1));
        assertEquals(-1, RoadTile.tileCoordinate(-512));
        assertEquals(-2, RoadTile.tileCoordinate(-513));
    }

    @Test
    void profileUpsertFromSurveyKeepsIdentityAndTakesLearnedShape() {
        ProposedProfile.Material gravel = new ProposedProfile.Material("GRAVEL", RoadMaterialRole.SURFACE, false, 0.8, 0.2, 40);
        RoadProfile existing = new RoadProfile(1, "Default road", RoadClass.ROAD, 1.0, List.of(), 1, 7, 0, true,
            List.of(5), null, null, null);
        ProposedProfile learned = new ProposedProfile(List.of(gravel), 3, 5, 40);

        RoadProfileUpsert upsert = RoadProfileUpsert.of(existing, learned, "{\"version\":1}");

        assertEquals("Default road", upsert.name());
        assertEquals(RoadClass.ROAD, upsert.roadClass());
        assertEquals(List.of(5), upsert.scopeTownIds());
        assertEquals(List.of(gravel), upsert.materials());
        assertEquals(3, upsert.widthMin());
        assertEquals(5, upsert.widthMax());
        assertEquals(40, upsert.sampleCount());
        assertEquals("{\"version\":1}", upsert.statsJson());
        assertFalse(existing.hasStats());
        assertTrue(new RoadProfile(1, "x", RoadClass.PATH, 2, List.of(), 1, 1, 0, true, List.of(),
            "{\"version\":1}", null, null).hasStats());

        RoadProfileUpsert fresh = RoadProfileUpsert.of("Trail", RoadClass.PATH, 1.4, List.of(), learned, null);
        assertTrue(fresh.enabled());
        assertNull(fresh.statsJson());
        assertThrows(IllegalArgumentException.class,
            () -> new RoadProfileUpsert("x", RoadClass.ROAD, 0, List.of(), 1, 7, 0, true, List.of(), null));
        assertThrows(IllegalArgumentException.class,
            () -> new RoadProfileUpsert("x", RoadClass.ROAD, 1, List.of(), 5, 3, 0, true, List.of(), null));
    }

    @Test
    void updateRecordsRefuseContradictions() {
        assertThrows(IllegalArgumentException.class, () -> new RoadNodeUpdate("Gate", true, null, null));
        assertEquals(new RoadNodeUpdate(null, true, null, null), RoadNodeUpdate.unnamed());
        assertEquals(Boolean.FALSE, RoadNodeUpdate.locked(false).locked());
        // Rev. 5: a move needs all three coordinates; clearing and setting a plaza exclude each other.
        assertThrows(IllegalArgumentException.class, () -> new RoadNodeUpdate(null, false, null, null, 1, null, 3, null, false));
        assertThrows(IllegalArgumentException.class, () -> new RoadNodeUpdate(null, false, null, null, null, null, null, 8, true));
        RoadNodeUpdate move = RoadNodeUpdate.moveTo(10, 64, -5);
        assertEquals(List.of(10, 64, -5), List.of(move.x(), move.y(), move.z()));
        assertEquals(Integer.valueOf(12), RoadNodeUpdate.plaza(12).plazaRadius());
        assertTrue(RoadNodeUpdate.noPlaza().clearPlaza());
        assertThrows(IllegalArgumentException.class, () -> new RoadNode(1, 0, 64, 0, RoadNodeKind.JUNCTION, null, 1, true, -1));
        assertTrue(new RoadNode(1, 0, 64, 0, RoadNodeKind.JUNCTION, null, 1, true, 9).isPlazaCentre());
        assertFalse(new RoadNode(1, 0, 64, 0, RoadNodeKind.JUNCTION, null, 1, true).isPlazaCentre());

        assertThrows(IllegalArgumentException.class, () -> new RoadEdgeUpdate(4, true, false, null, false, null, null));
        assertThrows(IllegalArgumentException.class, () -> new RoadEdgeUpdate(null, false, false, 2, true, null, null));
        assertThrows(IllegalArgumentException.class, () -> RoadEdgeUpdate.costMultiplier(0));
        RoadEdgeUpdate flags = RoadEdgeUpdate.flags(Set.of(RoadEdgeFlag.ONEWAY, RoadEdgeFlag.CLOSED));
        assertEquals(Set.of(RoadEdgeFlag.ONEWAY, RoadEdgeFlag.CLOSED), flags.flags());
        assertNull(RoadEdgeUpdate.street(9, true).flags());
        assertTrue(RoadEdgeUpdate.street(9, true).propagate());
        assertTrue(RoadEdgeUpdate.unlabelled().clearStreet());
    }

    @Test
    void recordedEdgeCopiesGeometryAndNeedsTwoPoints() {
        int[] a = {0, 64, 0};
        RoadEdgeRecord record = new RoadEdgeRecord("world", List.of(a, new int[] {5, 64, 0}), OptionalDouble.empty(),
            1.0, OptionalInt.empty(), OptionalInt.of(3), List.of(), List.of(), List.of("town_a"));
        a[0] = 99;
        assertEquals(0, record.geometry().get(0)[0]);
        assertThrows(IllegalArgumentException.class, () -> new RoadEdgeRecord("world", List.of(a),
            OptionalDouble.empty(), 1.0, OptionalInt.empty(), OptionalInt.empty(), List.of(), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new RoadEdgeRecord("world",
            List.of(new int[] {0, 0}, new int[] {1, 1}), OptionalDouble.empty(), 1.0, OptionalInt.empty(),
            OptionalInt.empty(), List.of(), List.of(), List.of()));
    }

    @Test
    void seedFactoriesAndApiError() {
        RoadSeedCreate admin = RoadSeedCreate.admin("world", 1, 64, 2, "north gate");
        assertEquals(RoadSeedSource.ADMIN, admin.source());
        assertTrue(admin.surveyId().isEmpty());
        RoadSeedCreate survey = RoadSeedCreate.survey("world", 1, 64, 2, 12);
        assertEquals(OptionalInt.of(12), survey.surveyId());
        assertNull(survey.note());

        RoadApiError error = new RoadApiError(RoadApiError.CONFLICT, null);
        assertTrue(error.isConflict());
        assertFalse(error.isNotFound());
        assertEquals("", error.message());
    }
}

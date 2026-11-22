package net.knightsandkings.knk.core.navigation.model;

import static net.knightsandkings.knk.core.navigation.model.ModelFixtures.T0;
import static net.knightsandkings.knk.core.navigation.model.ModelFixtures.model;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.core.navigation.model.NavigationModelSnapshot.Loaded.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Destination engine DESIGN § 7.2, § 7.3 (A11, A13): the snapshot file, schema 1. */
class NavigationModelSnapshotTest {

    @TempDir
    Path dir;

    private NavigationModelSnapshot snapshot() {
        return new NavigationModelSnapshot(dir.resolve(NavigationModelSnapshot.RELATIVE_PATH));
    }

    @Test
    void aModelSurvivesTheRoundTrip() throws Exception {
        NavigationModel model = model(T0).withPlace(3, null);
        NavigationModelSnapshot snapshot = snapshot();

        snapshot.write(model);
        NavigationModelSnapshot.Loaded loaded = snapshot.read();

        assertEquals(Status.LOADED, loaded.status());
        assertEquals(model, loaded.model(), "same content and asOf");
        assertTrue(loaded.model().roadAccessIgnored(9), "the road-access answers come back with the domains (E19)");
        assertTrue(loaded.model().roadsIgnoreRegion("mill_house"));
    }

    @Test
    void unknownPlacesAndLandmarksSurviveTheRoundTrip() throws Exception {
        DomainPlace unknown = new DomainPlace(5, "Keep", "Structure", null, null, null, null, null, false);
        Landmark throneRoom = new Landmark(12, "Throne Room", "world", 1.5, 70, -3, List.of(), 1,
            "Kardenna", true);
        NavigationModel model = new NavigationModel(T0, List.of(unknown), List.of(), null, List.of(throneRoom));
        NavigationModelSnapshot snapshot = snapshot();

        snapshot.write(model);

        assertEquals(model, snapshot.read().model(), "#708 fills the section without a new schema (E16)");
    }

    @Test
    void schemaOneHasEverySectionAndAnEmptyLandmarksList() throws Exception {
        String json = snapshot().toJson(model(T0));
        JsonNode root = new ObjectMapper().readTree(json);

        assertEquals(1, root.get("schema").intValue());
        assertEquals(T0.toString(), root.get("asOf").asText());
        for (String section : List.of("domains", "locations", "compass", "landmarks")) {
            assertTrue(root.has(section), section);
        }
        assertTrue(root.get("landmarks").isArray() && root.get("landmarks").isEmpty());
        JsonNode kardenna = root.get("domains").get(0);
        for (String field : List.of("id", "name", "domainType", "navigationDefault", "roadAccess", "wgRegionId", "world",
            "placeKnown", "spawn")) {
            assertTrue(kardenna.has(field), field);
        }
        for (String field : List.of("x", "y", "z", "yaw", "pitch", "world")) {
            assertTrue(kardenna.get("spawn").has(field), field);
        }
    }

    @Test
    void theFileHoldsNoPlayerData() throws Exception {
        String json = snapshot().toJson(model(T0)).toLowerCase();

        assertFalse(Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matcher(json).find(),
            "no UUIDs");
        for (String word : List.of("uuid", "player", "user", "username")) {
            assertFalse(json.contains("\"" + word), word);
        }
    }

    @Test
    void theWriteIsAtomicAndReplacesTheOldFile() throws Exception {
        NavigationModelSnapshot snapshot = snapshot();
        snapshot.write(model(T0));

        NavigationModel renamed = new NavigationModel(T0.plusSeconds(60),
            List.of(ModelFixtures.place(1, "Kardenna the Great", "Town", "Applies", "town_1", null)), List.of(), null, null);
        snapshot.write(renamed);

        assertEquals(renamed, snapshot.read().model());
        try (var files = Files.list(snapshot.file().getParent())) {
            assertEquals(List.of("navigation-model.json"), files.map(p -> p.getFileName().toString()).toList(),
                "the temporary file was moved over the old one, nothing left behind");
        }
    }

    @Test
    void aMissingFileIsMissing() {
        NavigationModelSnapshot.Loaded loaded = snapshot().read();

        assertEquals(Status.MISSING, loaded.status());
        assertNull(loaded.model());
    }

    @Test
    void aCorruptFileIsIgnoredNeverThrown() throws Exception {
        NavigationModelSnapshot snapshot = snapshot();
        Files.createDirectories(snapshot.file().getParent());
        for (String content : List.of("{ broken", "[]", "{}", "{\"schema\":1}", "{\"schema\":1,\"asOf\":\"yesterday\"}",
            "{\"schema\":0,\"asOf\":\"2026-11-18T12:00:00Z\"}")) {
            Files.writeString(snapshot.file(), content, StandardCharsets.UTF_8);

            NavigationModelSnapshot.Loaded loaded = snapshot.read();

            assertEquals(Status.CORRUPT, loaded.status(), content);
            assertNull(loaded.model());
        }
    }

    @Test
    void aNewerSchemaIsIgnored() throws Exception {
        NavigationModelSnapshot snapshot = snapshot();
        Files.createDirectories(snapshot.file().getParent());
        Files.writeString(snapshot.file(), "{\"schema\":2,\"asOf\":\"2026-11-18T12:00:00Z\",\"domains\":[],\"something\":{}}");

        NavigationModelSnapshot.Loaded loaded = snapshot.read();

        assertEquals(Status.NEWER_SCHEMA, loaded.status());
        assertTrue(loaded.detail().contains("schema 2"));
    }

    @Test
    void unknownFieldsAndBrokenEntriesOfSchemaOneAreSkipped() throws Exception {
        NavigationModelSnapshot snapshot = snapshot();
        Files.createDirectories(snapshot.file().getParent());
        Files.writeString(snapshot.file(), """
            {"schema":1,"asOf":"2026-11-18T12:00:00Z","extra":true,
             "domains":[{"id":1,"name":"Kardenna","domainType":"Town","placeKnown":true,"spawn":{"x":1,"y":2,"z":3,"world":"world"}},
                        {"id":2,"domainType":"Town"}],
             "locations":[{"id":41,"name":"Old Well","x":1,"y":2,"z":3}]}
            """);

        NavigationModel model = snapshot.read().model();

        assertEquals(List.of(1), model.domains().stream().map(DomainPlace::id).toList(), "a domain without a name is skipped");
        assertTrue(model.locations().isEmpty(), "a Location without a world is skipped");
        assertTrue(model.landmarks().isEmpty());
        assertEquals("world", model.domain(1).orElseThrow().spawn().world());
    }

    // ==================== off-road phase 5.2 (#709): the landmark's preference ====================

    @Test
    void aLandmarksPreferenceSurvivesTheRoundTrip() throws Exception {
        Landmark hall = new Landmark(14, "Great Hall", "world", 3.5, 70, 8.5, List.of("great_hall"),
            null, null, true, 2.5);
        Landmark garden = new Landmark(15, "Garden", "world", 30.5, 64, 8.5, List.of(), null, null,
            false, 0.5);
        NavigationModel model = new NavigationModel(T0, List.of(), List.of(), null, List.of(hall, garden));
        NavigationModelSnapshot snapshot = snapshot();

        snapshot.write(model);
        JsonNode written = new ObjectMapper().readTree(snapshot.file().toFile());
        NavigationModel read = snapshot.read().model();

        assertEquals(2.5, written.get("landmarks").get(0).get("preference").asDouble(), "the field is in the file");
        assertEquals(1, written.get("schema").asInt(), "still schema 1: the field is additive");
        assertEquals(model, read);
        assertEquals(List.of(2.5, 0.5), read.landmarks().stream().map(Landmark::preference).toList());
    }

    @Test
    void aSchemaOneFileWrittenBeforePhaseFiveTwoReadsTheMissingPreferenceAsOne() throws Exception {
        NavigationModelSnapshot snapshot = snapshot();
        Files.createDirectories(snapshot.file().getParent());
        Files.writeString(snapshot.file(), """
            {"schema":1,"asOf":"2026-11-18T12:00:00Z","domains":[],"locations":[],
             "landmarks":[{"id":12,"name":"Throne Room","world":"world","x":1.5,"y":70,"z":-3,"extent":"RADIUS",
                           "radius":4.0,"domainId":1,"domainName":"Kardenna","navigationEnabled":true},
                          {"id":13,"name":"Broken","world":"world","x":1.5,"y":70,"z":-3,"extent":"RADIUS",
                           "radius":4.0,"navigationEnabled":true,"preference":-2}]}
            """);

        List<Landmark> landmarks = snapshot.read().model().landmarks();

        assertEquals(List.of(1.0, 1.0), landmarks.stream().map(Landmark::preference).toList(),
            "missing = no weight; a broken value as well");
        assertFalse(landmarks.get(0).weighted());
    }

    // ==================== landmark-domain (PandiO/knk-workspace#985, LD08, LD22): the area's regions ====================

    @Test
    void theAreasRegionsAreWritten_AndAFileFromBeforeReadsAnOldRegionExtentAsThatRegion() throws Exception {
        NavigationModelSnapshot snapshot = snapshot();
        Landmark tower = new Landmark(16, "Tower", "world", 0, 64, 0, List.of("domain_16", "domain_16_sub_1"), 2, "Rivia",
            true, 2.0);
        snapshot.write(new NavigationModel(T0, List.of(), List.of(), null, List.of(tower)));
        JsonNode written = new ObjectMapper().readTree(snapshot.file().toFile()).get("landmarks").get(0);
        assertEquals("domain_16_sub_1", written.get("regionIds").get(1).asText());
        assertFalse(written.has("extent"), "the #708 fields are no longer written");
        assertFalse(written.has("regionId"));
        assertFalse(written.has("radius"));
        assertEquals(List.of(tower), snapshot.read().model().landmarks());

        Files.writeString(snapshot.file(), """
            {"schema":1,"asOf":"2026-11-18T12:00:00Z","domains":[],"locations":[],
             "landmarks":[{"id":12,"name":"Hall","world":"world","x":1.5,"y":70,"z":-3,"extent":"REGION",
                           "regionId":"great_hall","navigationEnabled":true},
                          {"id":13,"name":"Well","world":"world","x":1.5,"y":70,"z":-3,"extent":"RADIUS",
                           "radius":4.0,"navigationEnabled":true}]}
            """);
        List<Landmark> old = snapshot.read().model().landmarks();
        assertEquals(List.of("great_hall"), old.get(0).regionIds());
        assertEquals(List.of(), old.get(1).regionIds(), "a radius extent is a point now");
    }
}

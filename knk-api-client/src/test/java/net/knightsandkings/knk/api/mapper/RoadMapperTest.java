package net.knightsandkings.knk.api.mapper;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import net.knightsandkings.knk.api.dto.RoadEdgeDto;
import net.knightsandkings.knk.api.dto.RoadNetworkMetaDto;
import net.knightsandkings.knk.api.dto.RoadProfileDto;
import net.knightsandkings.knk.api.dto.RoadSeedDto;
import net.knightsandkings.knk.api.dto.RoadSeedLocationDto;
import net.knightsandkings.knk.api.dto.RoadSurveyDto;
import net.knightsandkings.knk.api.dto.RoadNodeDto;
import net.knightsandkings.knk.api.dto.RoadTileGraphDto;
import net.knightsandkings.knk.api.dto.RoadTileUpsertResultDto;
import net.knightsandkings.knk.core.domain.roads.RoadApiError;
import net.knightsandkings.knk.core.domain.roads.RoadBreadcrumbPoint;
import net.knightsandkings.knk.core.domain.roads.RoadClass;
import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeFlag;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeRecord;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeSource;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeUpdate;
import net.knightsandkings.knk.core.domain.roads.RoadMaterialRole;
import net.knightsandkings.knk.core.domain.roads.RoadNetworkMeta;
import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.domain.roads.RoadNodeAnchor;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import net.knightsandkings.knk.core.domain.roads.RoadNodeUpdate;
import net.knightsandkings.knk.core.domain.roads.RoadProfile;
import net.knightsandkings.knk.core.domain.roads.RoadProfileUpsert;
import net.knightsandkings.knk.core.domain.roads.RoadSeed;
import net.knightsandkings.knk.core.domain.roads.RoadSeedCreate;
import net.knightsandkings.knk.core.domain.roads.RoadSeedLocation;
import net.knightsandkings.knk.core.domain.roads.RoadSeedSource;
import net.knightsandkings.knk.core.domain.roads.RoadSurvey;
import net.knightsandkings.knk.core.domain.roads.RoadSurveyCreate;
import net.knightsandkings.knk.core.domain.roads.RoadTileGraph;
import net.knightsandkings.knk.core.domain.roads.RoadTileUpsertResult;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.roads.build.NodeMatcher;
import net.knightsandkings.knk.core.roads.build.ProfileSet;
import net.knightsandkings.knk.core.roads.build.SkeletonGraph;
import net.knightsandkings.knk.core.roads.build.TileBuildResult;
import net.knightsandkings.knk.core.roads.build.BuildWarning;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;
import net.knightsandkings.knk.core.roads.survey.ProposedProfile;
import net.knightsandkings.knk.core.roads.survey.SurveyStats;

/** Road navigation (KNG-27, plan 2e): DTO JSON matches knk-web-api's RoadDtos.cs and round-trips to knk-core records. */
class RoadMapperTest {

    private final ObjectMapper mapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .registerModule(new JavaTimeModule());

    private static String fixture(String name) throws Exception {
        try (InputStream in = RoadMapperTest.class.getResourceAsStream("/road/" + name)) {
            assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ---- tile graph download --------------------------------------------------------------

    @Test
    void tileGraphDownloadMapsTileNodesAndEdges() throws Exception {
        RoadTileGraph graph = RoadMapper.mapTileGraph(mapper.readValue(fixture("tile-graph.json"), RoadTileGraphDto.class));

        assertEquals(7, graph.tile().id());
        assertEquals("\"3\"", graph.etag());
        assertEquals(OffsetDateTime.of(2026, 9, 27, 19, 7, 50, 123456700, ZoneOffset.UTC), graph.tile().builtAt());
        assertTrue(graph.tile().isBuilt());
        assertEquals(List.of("label conflict at edge 12: Market Street vs High Street"), graph.tile().warnings());

        assertEquals(3, graph.nodes().size());
        RoadNode boundary = graph.nodes().get(0);
        assertEquals(RoadNodeKind.BOUNDARY, boundary.kind());
        assertNull(boundary.name());
        assertFalse(boundary.locked());
        assertNull(graph.nodes().get(1).name(), "a blank name is no name");
        RoadNode anchor = graph.nodes().get(2);
        assertEquals(RoadNodeKind.ANCHOR, anchor.kind());
        assertEquals("North gate", anchor.name());
        assertTrue(anchor.locked());
        assertTrue(anchor.isDestination());

        assertEquals(3, graph.edges().size());
        RoadEdge detected = graph.edges().get(0);
        assertEquals(11, detected.id());
        assertEquals(3, detected.geometry().size());
        assertArrayEquals(new int[] {100, 64, 101}, detected.geometry().get(1));
        assertEquals(200.5, detected.length());
        assertEquals(OptionalInt.of(1), detected.profileId());
        assertEquals(OptionalInt.of(4), detected.streetId());
        assertEquals(Set.of(), detected.flags());
        assertEquals(List.of(5), detected.domainIds());
        assertEquals(List.of("town_rivia"), detected.regionIds());
        assertEquals(RoadEdgeSource.DETECTED, detected.source());
        assertFalse(detected.stale());

        RoadEdge recorded = graph.edges().get(1);
        assertEquals(OptionalInt.empty(), recorded.profileId());
        assertEquals(OptionalInt.empty(), recorded.streetId());
        assertEquals(Set.of(RoadEdgeFlag.ONEWAY, RoadEdgeFlag.CLOSED), recorded.flags());
        assertTrue(recorded.isOneway());
        assertEquals(List.of(9), recorded.gateDoorIds());
        assertEquals(1.5, recorded.costMultiplier());
        assertEquals(RoadEdgeSource.RECORDED, recorded.source());
        assertTrue(recorded.stale());

        RoadEdge stitch = graph.edges().get(2);
        assertTrue(stitch.isStitch());
        assertEquals(40, stitch.toNodeId(), "the neighbour tile's node id is kept for cross-tile resolution");
        assertEquals(Set.of(), stitch.flags(), "\"None\" is not a flag");
    }

    @Test
    void tileGraphAdaptsToThePreviousGraphAndAnchorsOfARebuild() throws Exception {
        RoadTileGraph graph = RoadMapper.mapTileGraph(mapper.readValue(fixture("tile-graph.json"), RoadTileGraphDto.class));

        NodeMatcher.PreviousGraph previous = RoadMapper.toPreviousGraph(graph);
        assertEquals(3, previous.nodes().size());
        assertEquals(new NodeMatcher.PreviousNode(3, 300, 65, 120, RoadNodeKind.ANCHOR, true), previous.nodes().get(2));
        assertEquals(3, previous.edges().size(), "stitch edges may stay: their other node never matches");
        assertEquals(1, previous.edges().get(0).fromNodeId());
        assertEquals(2, previous.edges().get(0).toNodeId());
        assertEquals(3, previous.edges().get(0).geometry().size());
        assertEquals(NodeMatcher.PreviousGraph.EMPTY, RoadMapper.toPreviousGraph(null));

        List<SkeletonGraph.Anchor> anchors = RoadMapper.toAnchors(graph);
        assertEquals(List.of(new SkeletonGraph.Anchor(3, 300, 65, 120)), anchors);
        assertEquals(List.of(), RoadMapper.toAnchors(null));
    }

    @Test
    void plazaCentresAndNodeMovesMapBothWays() throws Exception {
        String json = "{\"id\":7,\"world\":\"w\",\"x\":10,\"y\":64,\"z\":20,\"tileId\":1,\"kind\":\"Junction\","
            + "\"source\":\"Detected\",\"componentId\":7,\"locked\":true,\"plazaRadius\":12}";
        RoadNode plaza = RoadMapper.mapNode(mapper.readValue(json, RoadNodeDto.class));
        assertEquals(12, plaza.plazaRadius());
        RoadNode plain = RoadMapper.mapNode(mapper.readValue(json.replace(",\"plazaRadius\":12", ""), RoadNodeDto.class));
        assertEquals(0, plain.plazaRadius());

        RoadTileGraph graph = RoadMapper.mapTileGraph(mapper.readValue(fixture("tile-graph.json"), RoadTileGraphDto.class));
        RoadNode anchor = graph.nodes().get(2);
        RoadNode endpoint = new RoadNode(9, 400, 64, 120, RoadNodeKind.ENDPOINT, null, 9, true, 5); // not a valid centre kind
        RoadTileGraph withPlazas = new RoadTileGraph(graph.tile(), List.of(plaza, endpoint,
            new RoadNode(anchor.id(), anchor.x(), anchor.y(), anchor.z(), anchor.kind(), anchor.name(), anchor.componentId(), true, 6)),
            graph.edges());
        assertEquals(List.of(new SkeletonGraph.Plaza(7, 10, 64, 20, 12), new SkeletonGraph.Plaza(3, 300, 65, 120, 6)),
            RoadMapper.toPlazas(withPlazas));
        assertEquals(List.of(), RoadMapper.toPlazas(graph));
        assertEquals(List.of(), RoadMapper.toPlazas(null));

        String move = mapper.writeValueAsString(RoadMapper.toNodeUpdateDto(RoadNodeUpdate.moveTo(1, 2, 3)));
        assertTrue(move.contains("\"x\":1") && move.contains("\"y\":2") && move.contains("\"z\":3"), move);
        assertFalse(move.contains("plazaRadius"), move);
        assertTrue(mapper.writeValueAsString(RoadMapper.toNodeUpdateDto(RoadNodeUpdate.plaza(9))).contains("\"plazaRadius\":9"));
        assertTrue(mapper.writeValueAsString(RoadMapper.toNodeUpdateDto(RoadNodeUpdate.noPlaza())).contains("\"clearPlaza\":true"));
    }

    @Test
    void edgeWithNullListsMapsToEmptyLists() throws Exception {
        String json = "{\"id\":5,\"fromNodeId\":1,\"toNodeId\":2,\"tileId\":1,\"world\":\"w\",\"geometry\":[[0,1,2],[3,4,5]],"
            + "\"length\":5,\"avgWidth\":1,\"costMultiplier\":1,\"source\":\"Detected\",\"status\":\"Ok\"}";
        RoadEdge edge = RoadMapper.mapEdge(mapper.readValue(json, RoadEdgeDto.class));
        assertEquals(List.of(), edge.gateDoorIds());
        assertEquals(List.of(), edge.domainIds());
        assertEquals(List.of(), edge.regionIds());
        assertEquals(Set.of(), edge.flags());
    }

    // ---- build upload ---------------------------------------------------------------------

    @Test
    void buildResultSerialisesToTheUpsertPayload() throws Exception {
        TileBuildResult build = new TileBuildResult(1, 10, 1,
            List.of(new TileBuildResult.Node("b0", OptionalInt.empty(), 0, 64, 100, RoadNodeKind.BOUNDARY),
                new TileBuildResult.Node("j", OptionalInt.of(2), 200, 64, 100, RoadNodeKind.JUNCTION)),
            List.of(new TileBuildResult.Edge(OptionalInt.of(11), "b0", "j", List.of(new int[] {0, 64, 100},
                new int[] {200, 64, 100}), 200, 3, OptionalInt.of(1), List.of(9), List.of(5), List.of("town_rivia")),
                new TileBuildResult.Edge(OptionalInt.empty(), "j", "b0", List.of(new int[] {200, 64, 100},
                    new int[] {0, 64, 100}), 200, 3, OptionalInt.empty(), List.of(), List.of(), List.of())),
            List.of(new BuildWarning("plaza too wide", 1, 2, 3)));

        JsonNode body = mapper.readTree(mapper.writeValueAsString(RoadMapper.toUpsertDto(build)));

        assertEquals(1, body.get("builderVersion").asInt());
        assertEquals(10, body.get("cellCount").asInt());
        assertEquals(1, body.get("levelCount").asInt());
        assertEquals(build.warningTexts().get(0), body.get("warnings").get(0).asText());
        JsonNode b0 = body.get("nodes").get(0);
        assertEquals("b0", b0.get("key").asText());
        assertFalse(b0.has("existingId"), "absent existingId is left out");
        assertEquals("Boundary", b0.get("kind").asText());
        assertEquals(2, body.get("nodes").get(1).get("existingId").asInt());
        assertEquals("Junction", body.get("nodes").get(1).get("kind").asText());
        JsonNode e0 = body.get("edges").get(0);
        assertEquals(11, e0.get("existingId").asInt());
        assertEquals("b0", e0.get("fromKey").asText());
        assertEquals("j", e0.get("toKey").asText());
        assertEquals(200, e0.get("geometry").get(1).get(0).asInt());
        assertEquals(64, e0.get("geometry").get(1).get(1).asInt());
        assertEquals(1, e0.get("profileId").asInt());
        assertEquals(9, e0.get("gateDoorIds").get(0).asInt());
        assertEquals(5, e0.get("domainIds").get(0).asInt());
        assertEquals("town_rivia", e0.get("regionIds").get(0).asText());
        JsonNode e1 = body.get("edges").get(1);
        assertFalse(e1.has("existingId"));
        assertFalse(e1.has("profileId"));
        assertEquals(0, e1.get("gateDoorIds").size());
    }

    @Test
    void upsertResultMapsCountsAndDeletedNodes() throws Exception {
        String json = "{\"tile\":{\"id\":7,\"world\":\"world\",\"tileX\":0,\"tileZ\":0,\"version\":4,\"builtAt\":null,"
            + "\"builderVersion\":1,\"dirty\":false,\"cellCount\":0,\"nodeCount\":0,\"edgeCount\":0,\"levelCount\":0,"
            + "\"warnings\":[]},\"nodesCreated\":3,\"nodesUpdated\":1,\"nodesDeleted\":1,\"edgesCreated\":2,"
            + "\"edgesUpdated\":0,\"edgesDeleted\":0,\"stitchEdges\":1,\"labelledEdges\":1,\"unlabelledEdges\":1,"
            + "\"conflicts\":[\"x\"],\"deletedNodes\":[{\"id\":9,\"world\":\"world\",\"x\":1,\"y\":2,\"z\":3,\"tileId\":7,"
            + "\"kind\":\"Endpoint\",\"source\":\"Detected\",\"componentId\":1,\"locked\":false}],\"bumpedTileIds\":[8]}";
        RoadTileUpsertResult result = RoadMapper.mapUpsertResult(mapper.readValue(json, RoadTileUpsertResultDto.class));
        assertEquals(4, result.tile().version());
        assertFalse(result.tile().isBuilt());
        assertEquals(3, result.nodesCreated());
        assertEquals(1, result.stitchEdges());
        assertEquals(List.of("x"), result.conflicts());
        assertEquals(9, result.deletedNodes().get(0).id());
        assertEquals(RoadNodeKind.ENDPOINT, result.deletedNodes().get(0).kind());
        assertEquals(List.of(8), result.bumpedTileIds());
    }

    // ---- profiles -------------------------------------------------------------------------

    @Test
    void profileMapsMaterialsScopeAndOpaqueStats() throws Exception {
        String json = "{\"id\":1,\"name\":\"Default road\",\"roadClass\":\"Road\",\"costMultiplier\":1.0,"
            + "\"materials\":[{\"material\":\"GRAVEL\",\"role\":\"Surface\",\"ambiguous\":false,\"centreShare\":0.8,"
            + "\"edgeShare\":0.1,\"samples\":40},{\"material\":\"STONE_BRICKS\",\"role\":\"Edge\",\"ambiguous\":true,"
            + "\"centreShare\":0.05,\"edgeShare\":0.6,\"samples\":12}],\"widthMin\":3,\"widthMax\":5,\"sampleCount\":40,"
            + "\"enabled\":true,\"scopeTownIds\":[5,7],\"stats\":{\"version\":1,\"samples\":40,\"floors\":{\"GRAVEL\":32}},"
            + "\"createdAt\":\"2026-09-27T10:00:00\",\"updatedAt\":\"2026-09-27T11:00:00Z\"}";
        RoadProfile profile = RoadMapper.mapProfile(mapper.readValue(json, RoadProfileDto.class));

        assertEquals("Default road", profile.name());
        assertEquals(RoadClass.ROAD, profile.roadClass());
        assertEquals(2, profile.materials().size());
        assertEquals(new ProposedProfile.Material("GRAVEL", RoadMaterialRole.SURFACE, false, 0.8, 0.1, 40),
            profile.materials().get(0));
        assertEquals(RoadMaterialRole.EDGE, profile.materials().get(1).role());
        assertTrue(profile.materials().get(1).ambiguous());
        assertEquals(List.of(5, 7), profile.scopeTownIds());
        assertTrue(profile.hasStats());
        assertEquals(1, mapper.readTree(profile.statsJson()).get("version").asInt());
        assertEquals(32, mapper.readTree(profile.statsJson()).get("floors").get("GRAVEL").asInt());
        assertEquals(OffsetDateTime.of(2026, 9, 27, 10, 0, 0, 0, ZoneOffset.UTC), profile.createdAt());

        ProfileSet.Profile builder = RoadMapper.toBuilderProfile(profile);
        assertEquals(1, builder.id());
        assertEquals(Set.of(5, 7), builder.scopeTownIds());
        assertEquals(profile.materials(), builder.materials());
        assertEquals(5, builder.widthMax());
        RoadNetworkSnapshot.Profile routing = RoadMapper.toSnapshotProfile(profile);
        assertEquals(new RoadNetworkSnapshot.Profile(1, "Default road", RoadClass.ROAD, 1.0), routing);
    }

    @Test
    void profileWithoutStatsHasNullStatsJson() throws Exception {
        String json = "{\"id\":2,\"name\":\"Trail\",\"roadClass\":\"Path\",\"costMultiplier\":1.4,\"materials\":[],"
            + "\"widthMin\":1,\"widthMax\":2,\"sampleCount\":0,\"enabled\":false,\"scopeTownIds\":null,\"stats\":null}";
        RoadProfile profile = RoadMapper.mapProfile(mapper.readValue(json, RoadProfileDto.class));
        assertNull(profile.statsJson());
        assertFalse(profile.hasStats());
        assertEquals(List.of(), profile.scopeTownIds());
        assertFalse(profile.enabled());
        assertEquals(RoadClass.PATH, profile.roadClass());
    }

    @Test
    void profileUpsertSerialisesStatsAsObjectOrLeavesItOut() throws Exception {
        ProposedProfile learned = new ProposedProfile(
            List.of(new ProposedProfile.Material("GRAVEL", RoadMaterialRole.SURFACE, false, 0.8, 0.1, 40)), 3, 5, 40);
        SurveyStats stats = SurveyStats.empty();
        RoadProfileUpsert upsert = RoadProfileUpsert.of("Wilderness road", RoadClass.ROAD, 1.0, List.of(5), learned,
            stats.toJson());

        JsonNode body = mapper.readTree(mapper.writeValueAsString(RoadMapper.toProfileUpsertDto(upsert)));
        assertEquals("Wilderness road", body.get("name").asText());
        assertEquals("Road", body.get("roadClass").asText());
        assertEquals(1.0, body.get("costMultiplier").asDouble());
        assertEquals("GRAVEL", body.get("materials").get(0).get("material").asText());
        assertEquals("Surface", body.get("materials").get(0).get("role").asText());
        assertEquals(0.8, body.get("materials").get(0).get("centreShare").asDouble());
        assertEquals(40, body.get("materials").get(0).get("samples").asInt());
        assertEquals(3, body.get("widthMin").asInt());
        assertEquals(5, body.get("widthMax").asInt());
        assertEquals(40, body.get("sampleCount").asInt());
        assertTrue(body.get("enabled").asBoolean());
        assertEquals(5, body.get("scopeTownIds").get(0).asInt());
        assertTrue(body.get("stats").isObject(), "stats travels as a JSON object, not a string");
        assertEquals(SurveyStats.VERSION, body.get("stats").get("version").asInt());
        assertEquals(stats, SurveyStats.fromJson(body.get("stats").toString()), "the API's echo reads back");

        JsonNode keep = mapper.readTree(mapper.writeValueAsString(
            RoadMapper.toProfileUpsertDto(RoadProfileUpsert.of("x", RoadClass.PATH, 1, List.of(), learned, null))));
        assertFalse(keep.has("stats"), "null stats is left out so a PUT keeps the stored stats (plan D5)");
        assertThrows(IllegalArgumentException.class, () -> RoadMapper.toProfileUpsertDto(
            RoadProfileUpsert.of("x", RoadClass.PATH, 1, List.of(), learned, "not json")));
    }

    // ---- surveys, seeds, meta -------------------------------------------------------------

    @Test
    void surveyCreateSerialisesIsoDatesAndBreadcrumb() throws Exception {
        OffsetDateTime started = OffsetDateTime.of(2026, 9, 27, 20, 0, 0, 0, ZoneOffset.UTC);
        RoadSurveyCreate survey = new RoadSurveyCreate("world", OptionalInt.of(1), started, started.plusMinutes(5), 2,
            List.of(new RoadBreadcrumbPoint(0, 64, 0, true), new RoadBreadcrumbPoint(1, 64, 0, false)),
            SurveyStats.empty().toJson());

        JsonNode body = mapper.readTree(mapper.writeValueAsString(RoadMapper.toSurveyCreateDto(survey)));
        assertEquals("world", body.get("world").asText());
        assertEquals(1, body.get("profileId").asInt());
        assertTrue(body.get("startedAt").isTextual(), "dates go as ISO strings, not timestamps");
        assertEquals(started, OffsetDateTime.parse(body.get("startedAt").asText()));
        assertEquals(started.plusMinutes(5), OffsetDateTime.parse(body.get("endedAt").asText()));
        assertEquals(2, body.get("sampleCount").asInt());
        assertTrue(body.get("breadcrumb").get(0).get("onRoad").asBoolean());
        assertFalse(body.get("breadcrumb").get(1).get("onRoad").asBoolean());
        assertTrue(body.get("stats").isObject());

        JsonNode noProfile = mapper.readTree(mapper.writeValueAsString(RoadMapper.toSurveyCreateDto(
            new RoadSurveyCreate("world", OptionalInt.empty(), started, null, 0, List.of(), null))));
        assertFalse(noProfile.has("profileId"));
        assertFalse(noProfile.has("endedAt"));
        assertFalse(noProfile.has("stats"));
    }

    @Test
    void surveyDtoMapsOptionalIdsAndStats() throws Exception {
        String json = "{\"id\":3,\"world\":\"world\",\"profileId\":null,\"startedByUserId\":12,"
            + "\"startedAt\":\"2026-09-27T20:00:00\",\"endedAt\":null,\"sampleCount\":1,"
            + "\"breadcrumb\":[{\"x\":1,\"y\":64,\"z\":2,\"onRoad\":true}],\"stats\":{\"version\":1}}";
        RoadSurvey survey = RoadMapper.mapSurvey(mapper.readValue(json, RoadSurveyDto.class));
        assertEquals(OptionalInt.empty(), survey.profileId());
        assertEquals(OptionalInt.of(12), survey.startedByUserId());
        assertNull(survey.endedAt());
        assertEquals(1, survey.breadcrumb().size());
        assertTrue(survey.breadcrumb().get(0).onRoad());
        assertEquals("{\"version\":1}", survey.statsJson());
    }

    @Test
    void seedsAndSeedLocationsRoundTrip() throws Exception {
        String json = "{\"id\":4,\"world\":\"world\",\"x\":1,\"y\":64,\"z\":2,\"source\":\"Survey\",\"surveyId\":3,"
            + "\"note\":null,\"createdAt\":\"2026-09-27T20:00:00Z\"}";
        RoadSeed seed = RoadMapper.mapSeed(mapper.readValue(json, RoadSeedDto.class));
        assertEquals(RoadSeedSource.SURVEY, seed.source());
        assertEquals(OptionalInt.of(3), seed.surveyId());
        assertNull(seed.note());

        JsonNode admin = mapper.readTree(mapper.writeValueAsString(
            RoadMapper.toSeedCreateDto(RoadSeedCreate.admin("world", 1, 64, 2, "north gate"))));
        assertEquals("Admin", admin.get("source").asText());
        assertEquals("north gate", admin.get("note").asText());
        assertFalse(admin.has("surveyId"));
        JsonNode fromSurvey = mapper.readTree(mapper.writeValueAsString(
            RoadMapper.toSeedCreateDto(RoadSeedCreate.survey("world", 1, 64, 2, 3))));
        assertEquals("Survey", fromSurvey.get("source").asText());
        assertEquals(3, fromSurvey.get("surveyId").asInt());
        assertFalse(fromSurvey.has("note"));

        RoadSeedLocation location = RoadMapper.mapSeedLocation(mapper.readValue(
            "{\"domainId\":9,\"domainType\":\"Town\",\"name\":\"Rivia\",\"x\":10,\"y\":64,\"z\":20}",
            RoadSeedLocationDto.class));
        assertEquals(new RoadSeedLocation(9, "Town", "Rivia", 10, 64, 20), location);
    }

    @Test
    void metaMapsProfilesStreetsAndComponents() throws Exception {
        String json = "{\"profiles\":[{\"id\":1,\"name\":\"Main\",\"roadClass\":\"Main\",\"costMultiplier\":0.8,"
            + "\"materials\":[],\"widthMin\":4,\"widthMax\":7,\"sampleCount\":0,\"enabled\":true}],"
            + "\"streets\":[{\"id\":4,\"name\":\"Market Street\"}],\"components\":[{\"id\":1,\"nodeCount\":5}]}";
        RoadNetworkMeta meta = RoadMapper.mapMeta(mapper.readValue(json, RoadNetworkMetaDto.class));
        assertEquals(1, meta.profiles().size());
        assertEquals(RoadClass.MAIN, meta.profiles().get(0).roadClass());
        assertEquals(new RoadNetworkSnapshot.Street(4, "Market Street"), meta.streets().get(0));
        assertEquals(5, meta.components().get(0).nodeCount());
        assertEquals(new RoadNetworkSnapshot.Profile(1, "Main", RoadClass.MAIN, 0.8),
            RoadMapper.toSnapshotProfile(meta.profiles().get(0)));
    }

    // ---- review commands ------------------------------------------------------------------

    @Test
    void reviewCommandsSerialiseOnlyWhatIsSet() throws Exception {
        JsonNode rename = mapper.readTree(mapper.writeValueAsString(RoadMapper.toNodeUpdateDto(RoadNodeUpdate.rename("Gate"))));
        assertEquals("Gate", rename.get("name").asText());
        assertFalse(rename.get("clearName").asBoolean());
        assertFalse(rename.has("kind"));
        assertFalse(rename.has("locked"));
        JsonNode unlock = mapper.readTree(mapper.writeValueAsString(RoadMapper.toNodeUpdateDto(
            new RoadNodeUpdate(null, true, RoadNodeKind.ANCHOR, false))));
        assertTrue(unlock.get("clearName").asBoolean());
        assertEquals("Anchor", unlock.get("kind").asText());
        assertFalse(unlock.get("locked").asBoolean());

        JsonNode anchor = mapper.readTree(mapper.writeValueAsString(RoadMapper.toAnchorDto(
            new RoadNodeAnchor("world", 1, 64, 2, null))));
        assertEquals(64, anchor.get("y").asInt());
        assertFalse(anchor.has("name"));

        JsonNode street = mapper.readTree(mapper.writeValueAsString(RoadMapper.toEdgeUpdateDto(RoadEdgeUpdate.street(4, true))));
        assertEquals(4, street.get("streetId").asInt());
        assertTrue(street.get("propagate").asBoolean());
        assertFalse(street.get("clearStreet").asBoolean());
        assertFalse(street.has("flags"));
        assertFalse(street.has("costMultiplier"));
        JsonNode flags = mapper.readTree(mapper.writeValueAsString(RoadMapper.toEdgeUpdateDto(
            RoadEdgeUpdate.flags(Set.of(RoadEdgeFlag.CLOSED, RoadEdgeFlag.NO_GPS)))));
        assertEquals(List.of("NoGps", "Closed"), List.of(flags.get("flags").get(0).asText(), flags.get("flags").get(1).asText()));
        JsonNode clear = mapper.readTree(mapper.writeValueAsString(RoadMapper.toEdgeUpdateDto(RoadEdgeUpdate.flags(Set.of()))));
        assertEquals(0, clear.get("flags").size(), "an empty set is sent, it clears every flag");

        JsonNode record = mapper.readTree(mapper.writeValueAsString(RoadMapper.toEdgeRecordDto(new RoadEdgeRecord(
            "world", List.of(new int[] {0, 64, 0}, new int[] {5, 64, 0}), OptionalDouble.empty(), 1.0,
            OptionalInt.empty(), OptionalInt.of(4), List.of(), List.of(5), List.of("town_a")))));
        assertFalse(record.has("length"), "no length: the API uses the polyline length");
        assertFalse(record.has("profileId"));
        assertEquals(4, record.get("streetId").asInt());
        assertEquals(5, record.get("geometry").get(1).get(0).asInt());
        assertEquals("town_a", record.get("regionIds").get(0).asText());
        JsonNode withLength = mapper.readTree(mapper.writeValueAsString(RoadMapper.toEdgeRecordDto(new RoadEdgeRecord(
            "world", List.of(new int[] {0, 64, 0}, new int[] {5, 64, 0}), OptionalDouble.of(7.5), 2.0,
            OptionalInt.of(1), OptionalInt.empty(), List.of(), List.of(), List.of()))));
        assertEquals(7.5, withLength.get("length").asDouble());
    }

    // ---- errors ---------------------------------------------------------------------------

    @Test
    void errorReadsTheRoadControllersBody() {
        ApiException conflict = new ApiException("http://api/road-nodes/merge", 409, "Request failed",
            "{\"error\":\"Conflict\",\"message\":\"Nodes 1 and 2 are in different worlds.\"}");
        RoadApiError error = RoadMapper.error(conflict).orElseThrow();
        assertTrue(error.isConflict());
        assertEquals("Nodes 1 and 2 are in different worlds.", error.message());

        assertTrue(RoadMapper.error(new ApiException("url", 500, "x", "<html>")).isEmpty());
        assertTrue(RoadMapper.error(new ApiException("url", 400, "x", "{\"title\":\"One or more validation errors\"}")).isEmpty());
        assertTrue(RoadMapper.error(new ApiException("url", 401, "x", "")).isEmpty());
        assertTrue(RoadMapper.error(null).isEmpty());
    }
}

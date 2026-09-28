package net.knightsandkings.knk.api.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import net.knightsandkings.knk.api.auth.NoAuthProvider;
import net.knightsandkings.knk.api.mapper.RoadMapper;
import net.knightsandkings.knk.core.domain.roads.RoadApiError;
import net.knightsandkings.knk.core.domain.roads.RoadBreadcrumbPoint;
import net.knightsandkings.knk.core.domain.roads.RoadClass;
import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeFlag;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeRecord;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeSource;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeUpdate;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeUpdateResult;
import net.knightsandkings.knk.core.domain.roads.RoadMaterialRole;
import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.domain.roads.RoadNodeAnchor;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import net.knightsandkings.knk.core.domain.roads.RoadNodeUpdate;
import net.knightsandkings.knk.core.domain.roads.RoadProfile;
import net.knightsandkings.knk.core.domain.roads.RoadProfileUpsert;
import net.knightsandkings.knk.core.domain.roads.RoadSeed;
import net.knightsandkings.knk.core.domain.roads.RoadSeedCreate;
import net.knightsandkings.knk.core.domain.roads.RoadSurvey;
import net.knightsandkings.knk.core.domain.roads.RoadSurveyCreate;
import net.knightsandkings.knk.core.domain.roads.RoadTile;
import net.knightsandkings.knk.core.domain.roads.RoadTileUpsertResult;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.roads.build.TileBuildResult;
import net.knightsandkings.knk.core.roads.survey.ProposedProfile;
import net.knightsandkings.knk.core.roads.survey.SurveyStats;
import okio.Buffer;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Road navigation (KNG-27) writes: routes, verbs, headers and bodies of plan Phase 1.5; error mapping. */
class RoadNetworkCommandApiImplTest {

    private static final String NODE_JSON = "{\"id\":3,\"world\":\"world\",\"x\":300,\"y\":65,\"z\":120,\"tileId\":7,"
        + "\"kind\":\"Anchor\",\"source\":\"Manual\",\"name\":\"North gate\",\"componentId\":1,\"locked\":true}";
    private static final String EDGE_JSON = "{\"id\":12,\"fromNodeId\":2,\"toNodeId\":3,\"tileId\":7,\"world\":\"world\","
        + "\"geometry\":[[200,64,100],[300,65,120]],\"length\":102.0,\"avgWidth\":2.0,\"profileId\":1,\"streetId\":4,"
        + "\"streetSource\":\"Manual\",\"costMultiplier\":1.0,\"flags\":[\"Oneway\"],\"gateDoorIds\":[],\"domainIds\":[],"
        + "\"regionIds\":[],\"source\":\"Recorded\",\"status\":\"Ok\"}";
    private static final String TILE_JSON = "{\"id\":7,\"world\":\"world\",\"tileX\":0,\"tileZ\":0,\"version\":4,"
        + "\"builtAt\":\"2026-09-27T19:07:50\",\"builderVersion\":1,\"dirty\":false,\"cellCount\":10,\"nodeCount\":3,"
        + "\"edgeCount\":2,\"levelCount\":1,\"warnings\":[]}";
    private static final String PROFILE_JSON = "{\"id\":2,\"name\":\"Wilderness road\",\"roadClass\":\"Road\","
        + "\"costMultiplier\":1.0,\"materials\":[{\"material\":\"GRAVEL\",\"role\":\"Surface\",\"ambiguous\":false,"
        + "\"centreShare\":0.8,\"edgeShare\":0.1,\"samples\":40}],\"widthMin\":3,\"widthMax\":5,\"sampleCount\":40,"
        + "\"enabled\":true,\"scopeTownIds\":[],\"stats\":{\"version\":1},\"createdAt\":\"2026-09-27T20:00:00Z\","
        + "\"updatedAt\":\"2026-09-27T20:00:00Z\"}";

    private final ObjectMapper mapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .registerModule(new JavaTimeModule());
    private final List<Request> seen = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private int status = 200;
    private String responseJson = "{}";

    private final OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
        Request request = chain.request();
        seen.add(request);
        if (request.body() != null) {
            Buffer buffer = new Buffer();
            request.body().writeTo(buffer);
            bodies.add(buffer.readUtf8());
        } else {
            bodies.add(null);
        }
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("x")
            .body(ResponseBody.create(status == 204 ? "" : responseJson, MediaType.get("application/json"))).build();
    }).build();

    private final RoadNetworkCommandApiImpl api = new RoadNetworkCommandApiImpl("http://api.test/api", client, mapper,
        new NoAuthProvider(), executor, false);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    private JsonNode body(int i) throws Exception {
        return mapper.readTree(bodies.get(i));
    }

    private static ApiException apiCause(CompletionException error) {
        Throwable t = error;
        while (t != null && !(t instanceof ApiException)) {
            t = t.getCause();
        }
        assertNotNull(t, "an ApiException is the cause");
        return (ApiException) t;
    }

    @Test
    void upsertTileGraphPutsTheBuildAndMapsTheResult() throws Exception {
        responseJson = "{\"tile\":" + TILE_JSON + ",\"nodesCreated\":3,\"nodesUpdated\":0,\"nodesDeleted\":0,"
            + "\"edgesCreated\":2,\"edgesUpdated\":0,\"edgesDeleted\":0,\"stitchEdges\":1,\"labelledEdges\":0,"
            + "\"unlabelledEdges\":2,\"conflicts\":[],\"deletedNodes\":[],\"bumpedTileIds\":[8]}";
        TileBuildResult build = new TileBuildResult(1, 10, 1,
            List.of(new TileBuildResult.Node("b0", OptionalInt.empty(), 0, 64, 100, RoadNodeKind.BOUNDARY),
                new TileBuildResult.Node("j", OptionalInt.empty(), 200, 64, 100, RoadNodeKind.JUNCTION)),
            List.of(new TileBuildResult.Edge(OptionalInt.empty(), "b0", "j",
                List.of(new int[] {0, 64, 100}, new int[] {200, 64, 100}), 200, 3, OptionalInt.of(1), List.of(),
                List.of(5), List.of("town_rivia"))),
            List.of());

        RoadTileUpsertResult result = api.upsertTileGraph("world", 0, 0, build).join();

        assertEquals("PUT", seen.get(0).method());
        assertEquals("http://api.test/api/road-tiles/world/0/0/graph", seen.get(0).url().toString());
        JsonNode body = body(0);
        assertEquals(1, body.get("builderVersion").asInt());
        assertEquals("b0", body.get("nodes").get(0).get("key").asText());
        assertEquals("Boundary", body.get("nodes").get(0).get("kind").asText());
        assertEquals("town_rivia", body.get("edges").get(0).get("regionIds").get(0).asText());
        assertEquals(3, result.nodesCreated());
        assertEquals(1, result.stitchEdges());
        assertEquals(4, result.tile().version());
        assertEquals(List.of(8), result.bumpedTileIds());
    }

    @Test
    void upsertValidationFailureCarriesTheApiMessage() {
        status = 400;
        responseJson = "{\"error\":\"ValidationFailed\",\"message\":\"Node 'b0' lies outside tile (0, 0).\"}";
        TileBuildResult build = new TileBuildResult(1, 0, 0, List.of(), List.of(), List.of());

        CompletionException error = assertThrows(CompletionException.class,
            () -> api.upsertTileGraph("world", 0, 0, build).join());

        ApiException cause = apiCause(error);
        assertEquals(400, cause.getStatusCode());
        RoadApiError apiError = RoadMapper.error(cause).orElseThrow();
        assertTrue(apiError.isValidationFailed());
        assertEquals("Node 'b0' lies outside tile (0, 0).", apiError.message());
    }

    @Test
    void markDirtyPostsWithoutBody() {
        responseJson = TILE_JSON.replace("\"dirty\":false", "\"dirty\":true");

        RoadTile tile = api.markDirty("world", 0, 0).join();

        assertEquals("POST", seen.get(0).method());
        assertEquals("http://api.test/api/road-tiles/world/0/0/dirty", seen.get(0).url().toString());
        assertEquals("", bodies.get(0));
        assertTrue(tile.dirty());
        assertEquals(4, tile.version());
    }

    @Test
    void createAndUpdateProfileSendTheUpsertBody() throws Exception {
        responseJson = PROFILE_JSON;
        ProposedProfile learned = new ProposedProfile(
            List.of(new ProposedProfile.Material("GRAVEL", RoadMaterialRole.SURFACE, false, 0.8, 0.1, 40)), 3, 5, 40);
        RoadProfileUpsert create = RoadProfileUpsert.of("Wilderness road", RoadClass.ROAD, 1.0, List.of(), learned,
            SurveyStats.empty().toJson());

        RoadProfile created = api.createProfile(create).join();

        assertEquals("POST", seen.get(0).method());
        assertEquals("http://api.test/api/road-profiles", seen.get(0).url().toString());
        assertEquals("Wilderness road", body(0).get("name").asText());
        assertEquals("Surface", body(0).get("materials").get(0).get("role").asText());
        assertTrue(body(0).get("stats").isObject());
        assertEquals(2, created.id());
        assertEquals("{\"version\":1}", created.statsJson());
        assertEquals(RoadMaterialRole.SURFACE, created.materials().get(0).role());

        RoadProfile updated = api.updateProfile(2, RoadProfileUpsert.of(created, learned, null)).join();
        assertEquals("PUT", seen.get(1).method());
        assertEquals("http://api.test/api/road-profiles/2", seen.get(1).url().toString());
        assertFalse(body(1).has("stats"), "null stats keeps the stored stats (plan D5)");
        assertEquals("Wilderness road", updated.name());
    }

    @Test
    void deletesAnswerTrueOn204AndFalseOn404() {
        status = 204;
        assertTrue(api.deleteProfile(2).join());
        assertEquals("DELETE", seen.get(0).method());
        assertEquals("http://api.test/api/road-profiles/2", seen.get(0).url().toString());
        assertTrue(api.deleteSeed(4).join());
        assertEquals("http://api.test/api/road-seeds/4", seen.get(1).url().toString());
        assertTrue(api.deleteEdge(12).join());
        assertEquals("http://api.test/api/road-edges/12", seen.get(2).url().toString());

        status = 404;
        responseJson = "{\"error\":\"NotFound\",\"message\":\"Road edge 99 not found.\"}";
        assertFalse(api.deleteEdge(99).join());
        assertFalse(api.deleteProfile(99).join());
        assertFalse(api.deleteSeed(99).join());

        status = 409;
        responseJson = "{\"error\":\"Conflict\",\"message\":\"Profile 1 is the default profile.\"}";
        CompletionException error = assertThrows(CompletionException.class, () -> api.deleteProfile(1).join());
        assertEquals(409, apiCause(error).getStatusCode());
    }

    @Test
    void createSurveySendsTheActingUserHeaderAndIsoDates() throws Exception {
        responseJson = "{\"id\":3,\"world\":\"world\",\"profileId\":2,\"startedByUserId\":12,"
            + "\"startedAt\":\"2026-09-27T20:00:00Z\",\"endedAt\":\"2026-09-27T20:05:00Z\",\"sampleCount\":2,"
            + "\"breadcrumb\":[{\"x\":0,\"y\":64,\"z\":0,\"onRoad\":true}],\"stats\":{\"version\":1}}";
        OffsetDateTime started = OffsetDateTime.of(2026, 9, 27, 20, 0, 0, 0, ZoneOffset.UTC);
        RoadSurveyCreate survey = new RoadSurveyCreate("world", OptionalInt.of(2), started, started.plusMinutes(5), 2,
            List.of(new RoadBreadcrumbPoint(0, 64, 0, true)), SurveyStats.empty().toJson());

        RoadSurvey stored = api.createSurvey(survey, 12).join();

        assertEquals("POST", seen.get(0).method());
        assertEquals("http://api.test/api/road-surveys", seen.get(0).url().toString());
        assertEquals("12", seen.get(0).header(UsersCommandApiImpl.ACTING_USER_HEADER));
        assertEquals("application/json", seen.get(0).header("Content-Type"));
        assertEquals(started, OffsetDateTime.parse(body(0).get("startedAt").asText()));
        assertTrue(body(0).get("breadcrumb").get(0).get("onRoad").asBoolean());
        assertEquals(3, stored.id());
        assertEquals(OptionalInt.of(12), stored.startedByUserId());

        api.createSurvey(survey, null).join();
        assertNull(seen.get(1).header(UsersCommandApiImpl.ACTING_USER_HEADER), "no actor, no header");
    }

    @Test
    void createSeedPostsTheSeed() throws Exception {
        responseJson = "{\"id\":4,\"world\":\"world\",\"x\":1,\"y\":64,\"z\":2,\"source\":\"Admin\",\"surveyId\":null,"
            + "\"note\":\"gate\",\"createdAt\":\"2026-09-27T20:00:00Z\"}";

        RoadSeed seed = api.createSeed(RoadSeedCreate.admin("world", 1, 64, 2, "gate")).join();

        assertEquals("http://api.test/api/road-seeds", seen.get(0).url().toString());
        assertEquals("Admin", body(0).get("source").asText());
        assertEquals(64, body(0).get("y").asInt());
        assertEquals(4, seed.id());
    }

    @Test
    void nodeReviewCallsUseTheirRoutes() throws Exception {
        responseJson = NODE_JSON;

        RoadNode renamed = api.updateNode(3, RoadNodeUpdate.rename("North gate")).join();
        assertEquals("PUT", seen.get(0).method());
        assertEquals("http://api.test/api/road-nodes/3", seen.get(0).url().toString());
        assertEquals("North gate", body(0).get("name").asText());
        assertFalse(body(0).has("locked"));
        assertEquals("North gate", renamed.name());
        assertTrue(renamed.locked());

        RoadNode anchor = api.createAnchor(new RoadNodeAnchor("world", 300, 65, 120, "North gate")).join();
        assertEquals("POST", seen.get(1).method());
        assertEquals("http://api.test/api/road-nodes/anchor", seen.get(1).url().toString());
        assertEquals(300, body(1).get("x").asInt());
        assertEquals(RoadNodeKind.ANCHOR, anchor.kind());

        RoadNode kept = api.mergeNodes(3, 9).join();
        assertEquals("POST", seen.get(2).method());
        assertEquals("http://api.test/api/road-nodes/merge", seen.get(2).url().toString());
        assertEquals(3, body(2).get("keepNodeId").asInt());
        assertEquals(9, body(2).get("mergeNodeId").asInt());
        assertEquals(3, kept.id());
    }

    @Test
    void edgeReviewCallsUseTheirRoutes() throws Exception {
        responseJson = EDGE_JSON;
        RoadEdge recorded = api.recordEdge(new RoadEdgeRecord("world",
            List.of(new int[] {200, 64, 100}, new int[] {300, 65, 120}), OptionalDouble.empty(), 2.0,
            OptionalInt.of(1), OptionalInt.of(4), List.of(), List.of(), List.of())).join();
        assertEquals("POST", seen.get(0).method());
        assertEquals("http://api.test/api/road-edges", seen.get(0).url().toString());
        assertFalse(body(0).has("length"));
        assertEquals(300, body(0).get("geometry").get(1).get(0).asInt());
        assertEquals(RoadEdgeSource.RECORDED, recorded.source());
        assertEquals(Set.of(RoadEdgeFlag.ONEWAY), recorded.flags());

        responseJson = "{\"edge\":" + EDGE_JSON + ",\"changedEdgeIds\":[12,13,14]}";
        RoadEdgeUpdateResult result = api.updateEdge(12, RoadEdgeUpdate.street(4, true)).join();
        assertEquals("PUT", seen.get(1).method());
        assertEquals("http://api.test/api/road-edges/12", seen.get(1).url().toString());
        assertEquals(4, body(1).get("streetId").asInt());
        assertTrue(body(1).get("propagate").asBoolean());
        assertEquals(OptionalInt.of(4), result.edge().streetId());
        assertEquals(List.of(12, 13, 14), result.changedEdgeIds());

        api.updateEdge(12, RoadEdgeUpdate.flags(Set.of(RoadEdgeFlag.CLOSED))).join();
        assertEquals("Closed", body(2).get("flags").get(0).asText());
        assertFalse(body(2).has("streetId"));
    }
}

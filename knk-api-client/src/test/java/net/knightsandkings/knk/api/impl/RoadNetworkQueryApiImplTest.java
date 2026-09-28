package net.knightsandkings.knk.api.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import net.knightsandkings.knk.core.domain.common.Conditional;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.roads.RoadApiError;
import net.knightsandkings.knk.core.domain.roads.RoadClass;
import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadNetworkMeta;
import net.knightsandkings.knk.core.domain.roads.RoadProfile;
import net.knightsandkings.knk.core.domain.roads.RoadSeed;
import net.knightsandkings.knk.core.domain.roads.RoadSeedLocation;
import net.knightsandkings.knk.core.domain.roads.RoadSeedSource;
import net.knightsandkings.knk.core.domain.roads.RoadSurvey;
import net.knightsandkings.knk.core.domain.roads.RoadTile;
import net.knightsandkings.knk.core.domain.roads.RoadTileGraph;
import net.knightsandkings.knk.core.exception.ApiException;
import okio.Buffer;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Road navigation (KNG-27) reads: routes and query strings of plan Phase 1.5, ETag/304 on the tile graph. */
class RoadNetworkQueryApiImplTest {

    private final ObjectMapper mapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .registerModule(new JavaTimeModule());
    private final List<Request> seen = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private int status = 200;
    private String responseJson = "[]";
    private String responseEtag = null;

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
        Response.Builder builder = new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status)
            .message("x").body(ResponseBody.create(status == 304 ? "" : responseJson, MediaType.get("application/json")));
        if (responseEtag != null) {
            builder.header("ETag", responseEtag);
        }
        return builder.build();
    }).build();

    private final RoadNetworkQueryApiImpl api = new RoadNetworkQueryApiImpl("http://api.test/api", client, mapper,
        new NoAuthProvider(), executor, false);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    private static String fixture(String name) throws Exception {
        try (InputStream in = RoadNetworkQueryApiImplTest.class.getResourceAsStream("/road/" + name)) {
            assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
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
    void tilesListsTheWorldsTiles() {
        responseJson = "[{\"id\":7,\"world\":\"world\",\"tileX\":0,\"tileZ\":-1,\"version\":3,\"builtAt\":\"2026-09-27T19:07:50\","
            + "\"builderVersion\":1,\"dirty\":true,\"cellCount\":10,\"nodeCount\":3,\"edgeCount\":2,\"levelCount\":1,\"warnings\":[]}]";

        List<RoadTile> tiles = api.tiles("world").join();

        assertEquals("GET", seen.get(0).method());
        assertEquals("http://api.test/api/road-tiles?world=world", seen.get(0).url().toString());
        assertEquals(1, tiles.size());
        assertEquals(-1, tiles.get(0).tileZ());
        assertTrue(tiles.get(0).dirty());
        assertEquals("\"3\"", tiles.get(0).etag());
    }

    @Test
    void worldNamesAreUrlEncoded() {
        responseJson = "[]";
        api.tiles("my world").join();
        assertEquals("http://api.test/api/road-tiles?world=my+world", seen.get(0).url().toString());
    }

    @Test
    void tileGraphDownloadsWithoutEtagAndReturnsTheServersEtag() throws Exception {
        responseJson = fixture("tile-graph.json");
        responseEtag = "\"3\"";

        Conditional<RoadTileGraph> result = api.tileGraph("world", 0, 0, null).join();

        assertEquals("http://api.test/api/road-tiles/world/0/0/graph", seen.get(0).url().toString());
        assertNull(seen.get(0).header(BaseApiImpl.IF_NONE_MATCH_HEADER));
        assertFalse(result.notModified());
        assertEquals("\"3\"", result.etag());
        assertEquals(3, result.body().nodes().size());
        assertEquals(3, result.body().edges().size());
        assertEquals(7, result.body().tile().id());
    }

    @Test
    void tileGraphSendsTheEtagAndMaps304ToNotModified() {
        status = 304;
        responseEtag = "\"3\"";

        Conditional<RoadTileGraph> result = api.tileGraph("world", 2, -3, "\"3\"").join();

        assertEquals("http://api.test/api/road-tiles/world/2/-3/graph", seen.get(0).url().toString());
        assertEquals("\"3\"", seen.get(0).header(BaseApiImpl.IF_NONE_MATCH_HEADER));
        assertTrue(result.notModified());
        assertNull(result.body());
        assertEquals("\"3\"", result.etag());
    }

    @Test
    void tileGraphWithoutServerEtagFallsBackToTheTileVersion() throws Exception {
        responseJson = fixture("tile-graph.json");
        responseEtag = null;

        Conditional<RoadTileGraph> result = api.tileGraph("world", 0, 0, "\"2\"").join();

        assertFalse(result.notModified());
        assertEquals("\"3\"", result.etag(), "the tile's version, quoted");
    }

    @Test
    void tileGraphNotBuiltCompletesExceptionallyWithTheApiError() {
        status = 404;
        responseJson = "{\"error\":\"NotFound\",\"message\":\"Tile (0, 0) of world 'world' has not been built.\"}";

        CompletionException error = assertThrows(CompletionException.class,
            () -> api.tileGraph("world", 0, 0, "\"1\"").join());

        ApiException cause = apiCause(error);
        assertEquals(404, cause.getStatusCode());
        RoadApiError body = RoadMapper.error(cause).orElseThrow();
        assertTrue(body.isNotFound());
        assertEquals("Tile (0, 0) of world 'world' has not been built.", body.message());
    }

    @Test
    void metaMapsProfilesStreetsAndComponents() {
        responseJson = "{\"profiles\":[{\"id\":1,\"name\":\"Default road\",\"roadClass\":\"Road\",\"costMultiplier\":1.0,"
            + "\"materials\":[],\"widthMin\":1,\"widthMax\":7,\"sampleCount\":0,\"enabled\":true}],"
            + "\"streets\":[{\"id\":4,\"name\":\"Market Street\"}],\"components\":[{\"id\":1,\"nodeCount\":5}]}";

        RoadNetworkMeta meta = api.meta("world").join();

        assertEquals("http://api.test/api/road-network/meta?world=world", seen.get(0).url().toString());
        assertEquals(RoadClass.ROAD, meta.profiles().get(0).roadClass());
        assertEquals("Market Street", meta.streets().get(0).name());
        assertEquals(5, meta.components().get(0).nodeCount());
    }

    @Test
    void profilesAndProfileById() {
        responseJson = "[{\"id\":1,\"name\":\"Default road\",\"roadClass\":\"Road\",\"costMultiplier\":1.0,\"materials\":[],"
            + "\"widthMin\":1,\"widthMax\":7,\"sampleCount\":0,\"enabled\":true,\"stats\":{\"version\":1}}]";
        List<RoadProfile> profiles = api.profiles().join();
        assertEquals("http://api.test/api/road-profiles", seen.get(0).url().toString());
        assertEquals(1, profiles.size());
        assertEquals("{\"version\":1}", profiles.get(0).statsJson());

        responseJson = "{\"id\":2,\"name\":\"Trail\",\"roadClass\":\"Path\",\"costMultiplier\":1.4,\"materials\":[],"
            + "\"widthMin\":1,\"widthMax\":2,\"sampleCount\":0,\"enabled\":true}";
        RoadProfile trail = api.profile(2).join();
        assertEquals("http://api.test/api/road-profiles/2", seen.get(1).url().toString());
        assertEquals(RoadClass.PATH, trail.roadClass());

        status = 404;
        responseJson = "{\"error\":\"NotFound\",\"message\":\"Road profile 9 not found.\"}";
        assertNull(api.profile(9).join(), "a 404 completes with null like the other getById calls");
    }

    @Test
    void surveysSeedsAndSeedLocationsUseTheirQueryStrings() {
        responseJson = "[{\"id\":3,\"world\":\"world\",\"profileId\":1,\"startedByUserId\":null,\"startedAt\":\"2026-09-27T20:00:00Z\","
            + "\"endedAt\":\"2026-09-27T20:05:00Z\",\"sampleCount\":12,\"breadcrumb\":[],\"stats\":null}]";
        List<RoadSurvey> surveys = api.surveys("world").join();
        assertEquals("http://api.test/api/road-surveys?world=world", seen.get(0).url().toString());
        assertEquals(12, surveys.get(0).sampleCount());
        assertTrue(surveys.get(0).startedByUserId().isEmpty());
        assertEquals(OffsetDateTime.parse("2026-09-27T20:05:00Z"), surveys.get(0).endedAt());

        responseJson = "[{\"id\":4,\"world\":\"world\",\"x\":1,\"y\":64,\"z\":2,\"source\":\"Admin\",\"surveyId\":null,"
            + "\"note\":\"gate\",\"createdAt\":\"2026-09-27T20:00:00Z\"}]";
        List<RoadSeed> seeds = api.seeds("world").join();
        assertEquals("http://api.test/api/road-seeds?world=world", seen.get(1).url().toString());
        assertEquals(RoadSeedSource.ADMIN, seeds.get(0).source());
        assertEquals("gate", seeds.get(0).note());

        responseJson = "[{\"domainId\":9,\"domainType\":\"Town\",\"name\":\"Rivia\",\"x\":10,\"y\":64,\"z\":20}]";
        List<RoadSeedLocation> locations = api.seedLocations("world", -16, -16, 527, 527).join();
        assertEquals("http://api.test/api/road-network/seed-locations?world=world&minX=-16&minZ=-16&maxX=527&maxZ=527",
            seen.get(2).url().toString());
        assertEquals("Rivia", locations.get(0).name());
    }

    @Test
    void searchEdgesPostsThePagedQueryAndMapsThePage() throws Exception {
        responseJson = "{\"items\":[{\"id\":5,\"fromNodeId\":1,\"toNodeId\":2,\"tileId\":7,\"world\":\"world\","
            + "\"geometry\":[[0,64,0],[5,64,0]],\"length\":5,\"avgWidth\":1,\"costMultiplier\":1,\"flags\":[],"
            + "\"source\":\"Detected\",\"status\":\"Stale\"}],\"totalCount\":1,\"pageNumber\":1,\"pageSize\":50}";

        Page<RoadEdge> page = api.searchEdges(new PagedQuery(1, 50, null, "length", true,
            Map.of("world", "world", "stale", "true"))).join();

        assertEquals("POST", seen.get(0).method());
        assertEquals("http://api.test/api/road-edges/search", seen.get(0).url().toString());
        JsonNode body = mapper.readTree(bodies.get(0));
        assertEquals("true", body.get("filters").get("stale").asText());
        assertEquals("length", body.get("sortBy").asText());
        assertTrue(body.get("sortDescending").asBoolean());
        assertEquals(1, page.totalCount());
        assertTrue(page.items().get(0).stale());
    }

    @Test
    void nullSearchQueryUsesTheFirstPage() throws Exception {
        responseJson = "{\"items\":[]}";
        Page<RoadEdge> page = api.searchEdges(null).join();
        JsonNode body = mapper.readTree(bodies.get(0));
        assertEquals(1, body.get("pageNumber").asInt());
        assertEquals(0, page.items().size());
        assertEquals(0, page.totalCount());
    }
}

package net.knightsandkings.knk.api.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.NoAuthProvider;
import net.knightsandkings.knk.core.domain.landmarks.KnkLandmark;
import net.knightsandkings.knk.core.domain.landmarks.LandmarkWrite;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.navigation.model.Landmark;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

/**
 * {@code api/landmarks} (off-road phase 5.1, PandiO/knk-workspace#708): the API's JSON (knk-web-api LandmarkReadDto,
 * as step A sends it) into {@link KnkLandmark} and the model's {@link Landmark}, and the write bodies.
 */
class LandmarksApiImplTest {

    /** A row as knk-web-api `claude/offroad-landmarks` serialises it. */
    static final String THRONE = """
        {"id":41,"name":"Throne room","locationId":31,
         "location":{"id":31,"name":"Throne room","x":100.5,"y":72.0,"z":-40.5,"yaw":90.0,"pitch":0.0,"world":"world"},
         "world":"world","domainId":9,"domain":{"id":9,"name":"Keep","domainType":"Structure","worldName":"world"},
         "domainName":"Keep","extentKind":"Radius","regionId":null,"radius":6.5,"preference":0.8,
         "navigationEnabled":true,"kind":"General","createdAt":"2026-11-18T15:00:00Z"}
        """;

    private final List<Request> seen = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private int status = 200;
    private String responseJson = "[]";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
        Request request = chain.request();
        seen.add(request);
        if (request.body() != null) {
            Buffer buffer = new Buffer();
            request.body().writeTo(buffer);
            bodies.add(buffer.readUtf8());
        }
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("x")
            .body(ResponseBody.create(responseJson, MediaType.get("application/json"))).build();
    }).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final LandmarksApiImpl api = new LandmarksApiImpl("http://api.test/api", client, mapper, new NoAuthProvider(),
        executor, false);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void listsAWorldsLandmarksWithWhatTheProviderNeeds() {
        responseJson = "[" + THRONE + ",{\"id\":42,\"name\":\"Old well\",\"location\":{\"x\":1,\"y\":64,\"z\":2,\"world\":\"world\"},"
            + "\"extentKind\":\"None\",\"navigationEnabled\":false,\"somethingNew\":1}]";

        List<KnkLandmark> landmarks = api.list("world nether").join();

        assertEquals("GET", seen.get(0).method());
        assertEquals("http://api.test/api/landmarks?world=world+nether", seen.get(0).url().toString());
        KnkLandmark throne = landmarks.get(0);
        assertEquals(41, throne.id());
        assertEquals(31, throne.locationId());
        assertEquals("world", throne.world());
        assertEquals(100.5, throne.x());
        assertEquals(("Keep" + "Structure" + 9), throne.domainName() + throne.domainType() + throne.domainId());
        assertEquals(Landmark.Extent.RADIUS, throne.extent());
        assertEquals(6.5, throne.radius());
        assertEquals(0.8, throne.preference());
        Landmark model = throne.toModel();
        assertEquals(new Landmark(41, "Throne room", "world", 100.5, 72.0, -40.5, Landmark.Extent.RADIUS, null, 6.5, 9, "Keep",
            true), model);
        assertFalse(landmarks.get(1).navigationEnabled());
        assertEquals(Landmark.Extent.NONE, landmarks.get(1).extent());
        assertEquals(1.0, landmarks.get(1).preference(), "missing: the API's default");
    }

    @Test
    void listsEveryWorldWithoutAQuery() {
        api.list(null).join();
        assertEquals("http://api.test/api/landmarks", seen.get(0).url().toString());
    }

    @Test
    void aMissingLandmarkIsEmpty() {
        status = 404;
        responseJson = "";
        assertTrue(api.get(5).join().isEmpty());
        assertEquals("http://api.test/api/landmarks/5", seen.get(0).url().toString());
    }

    @Test
    void createSendsTheNameThePinAndTheExtentInTheApisWords() throws Exception {
        responseJson = THRONE;
        LandmarkWrite write = new LandmarkWrite("Throne room", new LandmarkWrite.Pin(null, "world", 100.5, 72, -40.5, 90f, 0f),
            null, Landmark.Extent.REGION, "keep_throne", null, null);

        KnkLandmark created = api.create(write).join();

        assertEquals("POST", seen.get(0).method());
        JsonNode body = mapper.readTree(bodies.get(0));
        assertEquals("Throne room", body.get("name").asText());
        assertEquals("Region", body.get("extentKind").asText());
        assertEquals("keep_throne", body.get("regionId").asText());
        assertTrue(body.get("domainId").isNull(), "written as null");
        assertFalse(body.has("radius"));
        assertFalse(body.get("location").has("id") && !body.get("location").get("id").isNull(), "a new Location");
        assertEquals("world", body.get("location").get("world").asText());
        assertEquals(41, created.id());
    }

    @Test
    void anUpdateKeepsTheDomainAndMovesTheSameLocation() throws Exception {
        responseJson = "[" + THRONE + "]";
        KnkLandmark stored = api.list(null).join().get(0);
        responseJson = THRONE;

        api.update(41, LandmarkWrite.from(stored).withPin(new LandmarkWrite.Pin(stored.locationId(), "world", 1, 2, 3, 0f, 0f)))
            .join();

        Request put = seen.get(seen.size() - 1);
        assertEquals("PUT", put.method());
        assertEquals("http://api.test/api/landmarks/41", put.url().toString());
        JsonNode body = mapper.readTree(bodies.get(bodies.size() - 1));
        assertEquals(9, body.get("domainId").asInt(), "the domain is resent, or the API unlinks it");
        assertEquals(31, body.get("location").get("id").asInt());
        assertFalse(body.has("extentKind"), "null keeps the stored extent");
    }

    @Test
    void aRefusalCarriesTheApisReason() {
        status = 400;
        responseJson = "\"A landmark named \\\"Hall\\\" already exists in world world.\"";
        CompletionException error = assertThrows(CompletionException.class,
            () -> api.create(new LandmarkWrite("Hall", null, null, null, null, null, null)).join());
        ApiException cause = assertInstanceOf(ApiException.class, error.getCause());
        assertEquals(400, cause.getStatusCode());
        assertTrue(cause.getResponseBody().contains("already exists"));
    }

    @Test
    void deleteSendsADelete() {
        status = 204;
        responseJson = "";
        api.delete(41).join();
        assertEquals("DELETE", seen.get(0).method());
        assertEquals("http://api.test/api/landmarks/41", seen.get(0).url().toString());
    }
}

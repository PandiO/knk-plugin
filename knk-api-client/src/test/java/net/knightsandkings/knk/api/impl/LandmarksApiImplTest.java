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
import net.knightsandkings.knk.core.domain.landmarks.LandmarkAreaKind;
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
 * {@code api/landmarks}: the API's JSON (knk-web-api {@code LandmarkReadDto}, since PandiO/knk-workspace#985 the
 * landmark-domain names) into {@link KnkLandmark} and the model's {@link Landmark}, and the write bodies.
 */
class LandmarksApiImplTest {

    /**
     * A row as knk-web-api {@code next} (L1/L2 of the landmark-domain chain) serialises it: an own region with a
     * sub-region, a town as parent, and the #708 projection, which the plugin no longer reads.
     */
    static final String TOWER = """
        {"id":41,"domainType":"Landmark","name":"Throne room","description":"","locationId":31,
         "location":{"id":31,"name":"Throne room","x":100.5,"y":72.0,"z":-40.5,"yaw":90.0,"pitch":0.0,"world":"world"},
         "world":"world","worldName":"world","areaKind":"OwnRegion","wgRegionId":"domain_41",
         "subRegionIds":["domain_41_sub_3"],"subRegions":[],
         "parentDomainId":9,"parentDomain":{"id":9,"name":"Rivia","domainType":"Town","worldName":"world","wgRegionId":"town_9"},
         "parentDomainName":"Rivia","allowEntry":true,"allowExit":true,"showEnterLeaveMessages":true,
         "teleportEnabled":false,"teleportPriceGems":0,"teleportRequiresDiscovery":false,
         "preference":0.8,"navigationEnabled":true,"kind":"General","createdAt":"2026-11-22T15:00:00Z",
         "domainId":9,"domain":{"id":9,"name":"Rivia","domainType":"Town","worldName":"world","wgRegionId":"town_9"},
         "domainName":"Rivia","extentKind":"Region","regionId":"domain_41","radius":null}
        """;

    /** A LinkedDomain landmark: no region of its own; the projection names the parent's region. */
    static final String CROSS = """
        {"id":43,"domainType":"Landmark","name":"Market cross",
         "location":{"x":1,"y":64,"z":2,"world":"world"},"areaKind":"LinkedDomain","wgRegionId":null,"subRegionIds":[],
         "parentDomainId":9,"parentDomain":{"id":9,"name":"Rivia","domainType":"Town","worldName":"world","wgRegionId":"town_9"},
         "showEnterLeaveMessages":false,"preference":1.0,"navigationEnabled":true,
         "extentKind":"Region","regionId":"town_9","radius":null}
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
        responseJson = "[" + TOWER + "," + CROSS + ",{\"id\":42,\"name\":\"Old well\",\"location\":{\"x\":1,\"y\":64,\"z\":2,"
            + "\"world\":\"world\"},\"areaKind\":\"None\",\"navigationEnabled\":false,\"somethingNew\":1}]";

        List<KnkLandmark> landmarks = api.list("world nether").join();

        assertEquals("GET", seen.get(0).method());
        assertEquals("http://api.test/api/landmarks?world=world+nether", seen.get(0).url().toString());
        KnkLandmark tower = landmarks.get(0);
        assertEquals(41, tower.id());
        assertEquals(31, tower.locationId());
        assertEquals("world", tower.world());
        assertEquals(100.5, tower.x());
        assertEquals(("Rivia" + "Town" + 9 + "town_9"),
            tower.parentDomainName() + tower.parentDomainType() + tower.parentDomainId() + tower.parentRegionId());
        assertEquals(LandmarkAreaKind.OWN_REGION, tower.areaKind());
        assertEquals("domain_41", tower.wgRegionId());
        assertEquals(List.of("domain_41_sub_3"), tower.subRegionIds());
        assertTrue(tower.showEnterLeaveMessages());
        assertEquals(0.8, tower.preference());
        assertEquals(new Landmark(41, "Throne room", "world", 100.5, 72.0, -40.5, List.of("domain_41", "domain_41_sub_3"), 9,
            "Rivia", true, 0.8), tower.toModel(), "the area's union and the preference reach the model");

        KnkLandmark cross = landmarks.get(1);
        assertEquals(LandmarkAreaKind.LINKED_DOMAIN, cross.areaKind());
        assertEquals(List.of("town_9"), cross.toModel().regionIds(), "LinkedDomain: the parent's main region");
        assertFalse(cross.showEnterLeaveMessages());

        assertFalse(landmarks.get(2).navigationEnabled());
        assertEquals(LandmarkAreaKind.NONE, landmarks.get(2).areaKind());
        assertEquals(List.of(), landmarks.get(2).toModel().regionIds());
        assertEquals(1.0, landmarks.get(2).preference(), "missing: the API's default");
    }

    /** The #708 projection alone (an API from before L1) is no longer read: a later change drops it from the API. */
    @Test
    void theOldProjectionAloneIsNotRead() {
        responseJson = "[{\"id\":44,\"name\":\"Hall\",\"location\":{\"x\":1,\"y\":64,\"z\":2,\"world\":\"world\"},"
            + "\"domainId\":9,\"extentKind\":\"Region\",\"regionId\":\"hall\"}]";
        KnkLandmark hall = api.list(null).join().get(0);
        assertEquals(LandmarkAreaKind.NONE, hall.areaKind());
        assertEquals(List.of(), hall.areaRegionIds());
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
    void createSendsTheNameThePinAndTheAreaInTheApisNewWords() throws Exception {
        responseJson = TOWER;
        LandmarkWrite write = new LandmarkWrite("Throne room", new LandmarkWrite.Pin(null, "world", 100.5, 72, -40.5, 90f, 0f),
            null, LandmarkAreaKind.OWN_REGION, "keep_throne", null);

        KnkLandmark created = api.create(write).join();

        assertEquals("POST", seen.get(0).method());
        JsonNode body = mapper.readTree(bodies.get(0));
        assertEquals("Throne room", body.get("name").asText());
        assertEquals("OwnRegion", body.get("areaKind").asText());
        assertEquals("keep_throne", body.get("wgRegionId").asText());
        assertTrue(body.get("parentDomainId").isNull(), "written as null");
        for (String old : List.of("extentKind", "regionId", "radius", "domainId")) {
            assertFalse(body.has(old), "no #708 name: " + old);
        }
        assertFalse(body.get("location").has("id") && !body.get("location").get("id").isNull(), "a new Location");
        assertEquals("world", body.get("location").get("world").asText());
        assertEquals(41, created.id());
    }

    @Test
    void anUpdateKeepsTheParentAndMovesTheSameLocation() throws Exception {
        responseJson = "[" + TOWER + "]";
        KnkLandmark stored = api.list(null).join().get(0);
        responseJson = TOWER;

        api.update(41, LandmarkWrite.from(stored).withPin(new LandmarkWrite.Pin(stored.locationId(), "world", 1, 2, 3, 0f, 0f)))
            .join();

        Request put = seen.get(seen.size() - 1);
        assertEquals("PUT", put.method());
        assertEquals("http://api.test/api/landmarks/41", put.url().toString());
        JsonNode body = mapper.readTree(bodies.get(bodies.size() - 1));
        assertEquals(9, body.get("parentDomainId").asInt(), "the parent is resent, or the API unlinks it");
        assertEquals(31, body.get("location").get("id").asInt());
        assertFalse(body.has("areaKind"), "null keeps the stored area");
    }

    @Test
    void aRefusalCarriesTheApisReason() {
        status = 400;
        responseJson = "\"A landmark named \\\"Hall\\\" already exists in world world.\"";
        CompletionException error = assertThrows(CompletionException.class,
            () -> api.create(new LandmarkWrite("Hall", null, null, null, null, null)).join());
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

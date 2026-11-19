package net.knightsandkings.knk.api.impl;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.SiegeWeaponSpotsApi;
import net.knightsandkings.knk.core.siege.weapons.SpotBounds;
import net.knightsandkings.knk.core.siege.weapons.SpotFacing;
import net.knightsandkings.knk.core.siege.weapons.SpotSizeClass;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Siege weapons phase 2 (#783): the spot task's API calls against a local HTTP server. */
class SiegeWeaponSpotsApiImplTest {

    private HttpServer server;
    private ExecutorService executor;
    private SiegeWeaponSpotsApiImpl api;
    private final Map<String, String> bodies = new ConcurrentHashMap<>();
    private final Map<String, String> responses = new ConcurrentHashMap<>();
    private final Map<String, Integer> statuses = new ConcurrentHashMap<>();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api", exchange -> {
            String path = exchange.getRequestURI().getPath();
            bodies.put(exchange.getRequestMethod() + " " + path,
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responses.getOrDefault(path, "[]").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statuses.getOrDefault(path, 200), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        executor = Executors.newFixedThreadPool(2);
        ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        api = new SiegeWeaponSpotsApiImpl("http://127.0.0.1:" + server.getAddress().getPort() + "/api",
                new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build(), mapper, null, executor, false);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void listTeams_ReadsIdNameClanAndRole() throws Exception {
        responses.put("/api/SiegeScenarios/7/teams", """
                [{"id":11,"siegeScenarioId":7,"role":"Attacker","name":"Red","clanName":null},
                 {"id":12,"siegeScenarioId":7,"role":"Defender","name":null,"clanName":"Iron Wardens"}]""");
        List<SiegeWeaponSpotsApi.Team> teams = api.listTeams(7).get(5, TimeUnit.SECONDS);
        assertEquals(List.of(new SiegeWeaponSpotsApi.Team(11, "Red", null, "Attacker"),
                new SiegeWeaponSpotsApi.Team(12, null, "Iron Wardens", "Defender")), teams);
    }

    @Test
    void listTeams_UnknownScenario_IsNull() throws Exception {
        statuses.put("/api/SiegeScenarios/8/teams", 404);
        assertNull(api.listTeams(8).get(5, TimeUnit.SECONDS));
    }

    @Test
    void createSpot_PostsBoundsFacingAndClass_UnderTheScenario() throws Exception {
        responses.put("/api/SiegeScenarios/7/weapon-spots",
                "{\"id\":31,\"name\":\"North wall\",\"facing\":\"North\",\"sizeClass\":\"Small\"}");
        SiegeWeaponSpotsApi.CreatedSpot created = api.createSpot(7, new SiegeWeaponSpotsApi.NewSpot(11, "North wall",
                new SpotBounds(0, 64, 0, 6, 68, 8), null, SpotFacing.NORTH, SpotSizeClass.SMALL)).get(5, TimeUnit.SECONDS);

        assertEquals(new SiegeWeaponSpotsApi.CreatedSpot(31, "North wall", "North", "Small"), created);
        JsonNode body = json.readTree(bodies.get("POST /api/SiegeScenarios/7/weapon-spots"));
        assertEquals(11, body.get("siegeTeamId").asInt());
        assertEquals("North wall", body.get("name").asText());
        assertEquals(6, body.get("maxX").asInt());
        assertEquals(68, body.get("maxY").asInt());
        assertEquals("North", body.get("facing").asText());
        assertEquals("Small", body.get("sizeClass").asText());
        assertFalse(body.has("wgRegionId"));
    }

    @Test
    void createSpot_ARefusal_CarriesTheApiMessage() {
        statuses.put("/api/SiegeScenarios/7/weapon-spots", 400);
        responses.put("/api/SiegeScenarios/7/weapon-spots", "siegeTeamId must be a team of this scenario.");
        ExecutionException failure = assertThrows(ExecutionException.class, () -> api.createSpot(7,
                new SiegeWeaponSpotsApi.NewSpot(99, "x", new SpotBounds(0, 64, 0, 6, 68, 8), "r1", SpotFacing.EAST,
                        SpotSizeClass.SMALL)).get(5, TimeUnit.SECONDS));
        ApiException api = assertInstanceOf(ApiException.class, failure.getCause());
        assertEquals(400, api.getStatusCode());
        assertTrue(api.getResponseBody().contains("must be a team of this scenario"), api.getResponseBody());
    }
}

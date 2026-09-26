package net.knightsandkings.knk.api.impl;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.Completion;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.ObjectiveResult;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.Participant;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.ParticipantResult;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.ParticipantReward;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.RewardSummary;
import net.knightsandkings.knk.core.domain.siege.SiegeEndReason;
import net.knightsandkings.knk.core.exception.ApiException;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Siege Phase 6: SiegeMatchesCommandApiImpl against a local HTTP server - paths, JSON bodies (camelCase,
 * PascalCase enum names, ISO-8601 instants) and response/error mapping, with the client's real
 * ObjectMapper settings (JavaTimeModule, unknown properties ignored).
 */
class SiegeMatchesCommandApiImplTest {

    private HttpServer server;
    private ExecutorService executor;
    private SiegeMatchesCommandApiImpl api;
    private final List<String> requests = new ArrayList<>();
    private final Map<String, String> bodies = new ConcurrentHashMap<>();
    private final Map<String, Integer> statusByPath = new ConcurrentHashMap<>();
    private final Map<String, String> responseByPath = new ConcurrentHashMap<>();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            synchronized (requests) {
                requests.add(exchange.getRequestMethod() + " " + path);
            }
            bodies.put(path, body);
            int status = statusByPath.getOrDefault(path, 200);
            String response = responseByPath.getOrDefault(path, "");
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
            if (status != 204) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        executor = Executors.newFixedThreadPool(2);
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .registerModule(new JavaTimeModule());
        OkHttpClient http = new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build();
        api = new SiegeMatchesCommandApiImpl("http://127.0.0.1:" + server.getAddress().getPort() + "/api",
                http, mapper, null, executor, false);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        executor.shutdownNow();
    }

    private static <T> T await(java.util.concurrent.CompletableFuture<T> f) throws Exception {
        return f.get(5, TimeUnit.SECONDS);
    }

    @Test
    void create_postsLobbyAndScenario_andReturnsTheId() throws Exception {
        responseByPath.put("/api/siege-matches", "{\"id\":42,\"status\":\"Created\",\"siegeLobbyName\":\"x\"}");
        statusByPath.put("/api/siege-matches", 201);

        assertEquals(42L, await(api.createMatch(1, 100)));

        assertEquals(List.of("POST /api/siege-matches"), requests);
        JsonNode body = json.readTree(bodies.get("/api/siege-matches"));
        assertEquals(1, body.get("siegeLobbyId").asInt());
        assertEquals(100, body.get("siegeScenarioId").asInt());
    }

    @Test
    void start_left_abort_abortUnfinished_useTheirPaths() throws Exception {
        responseByPath.put("/api/siege-matches/42/start", "{\"id\":42,\"status\":\"InProgress\"}");
        statusByPath.put("/api/siege-matches/42/participants/7/left", 204);
        responseByPath.put("/api/siege-matches/42/abort", "{\"id\":42,\"status\":\"Aborted\"}");
        responseByPath.put("/api/siege-matches/abort-unfinished", "{\"abortedMatchIds\":[3,4]}");

        await(api.startMatch(42, List.of(new Participant(7, 202), new Participant(8, 201))));
        await(api.participantLeft(42, 7, Instant.parse("2026-09-26T21:00:00Z")));
        await(api.abortMatch(42, SiegeEndReason.ADMIN_STOPPED));
        assertEquals(List.of(3L, 4L), await(api.abortUnfinished(SiegeEndReason.SERVER_RESTART)));

        JsonNode start = json.readTree(bodies.get("/api/siege-matches/42/start"));
        assertEquals(2, start.get("participants").size());
        assertEquals(202, start.get("participants").get(0).get("siegeTeamId").asInt());
        assertEquals("2026-09-26T21:00:00Z", json.readTree(bodies.get("/api/siege-matches/42/participants/7/left")).get("leftAt").asText());
        assertEquals("AdminStopped", json.readTree(bodies.get("/api/siege-matches/42/abort")).get("endReason").asText());
        assertEquals("ServerRestart", json.readTree(bodies.get("/api/siege-matches/abort-unfinished")).get("endReason").asText());
    }

    @Test
    void complete_sendsTheCompletion_andMapsTheRewards() throws Exception {
        responseByPath.put("/api/siege-matches/42/complete", """
                {"matchId":42,"status":"Completed","alreadyCompleted":false,"rewards":[
                  {"userId":7,"siegeTeamId":202,"presentAtEnd":true,"won":true,"holdingCount":2,"captureCount":1,
                   "coins":250,"experience":25,"gems":1,"titleChange":{"direction":"promotion"}},
                  {"userId":8,"presentAtEnd":false,"won":false,"coins":0,"experience":0,"gems":0}]}""");
        Completion completion = new Completion(SiegeEndReason.INSTANT_VICTORY, 2,
                List.of(new ParticipantResult(7, 202, 3, 1, 2, 1)),
                List.of(new ObjectiveResult(501, 202, 7, Instant.parse("2026-09-26T21:05:00Z")),
                        new ObjectiveResult(502, 201, null, null)));

        RewardSummary summary = await(api.completeMatch(42, completion));

        assertEquals(42, summary.matchId());
        assertEquals(List.of(new ParticipantReward(7, true, true, 2, 1, 250, 25, 1),
                new ParticipantReward(8, false, false, 0, 0, 0, 0, 0)), summary.rewards());
        JsonNode body = json.readTree(bodies.get("/api/siege-matches/42/complete"));
        assertEquals("InstantVictory", body.get("endReason").asText());
        assertEquals(2, body.get("winningAllianceGroup").asInt());
        assertEquals(3, body.get("participants").get(0).get("kills").asInt());
        assertEquals(2, body.get("participants").get(0).get("highestKillStreak").asInt());
        assertEquals(501, body.get("objectives").get(0).get("siegeObjectiveId").asInt());
        assertEquals("2026-09-26T21:05:00Z", body.get("objectives").get(0).get("capturedAt").asText());
        assertTrue(body.get("objectives").get(1).get("capturedByUserId").isNull());
    }

    @Test
    void httpErrors_surfaceAsApiExceptionWithTheStatus() {
        statusByPath.put("/api/siege-matches/42/complete", 409);
        responseByPath.put("/api/siege-matches/42/complete", "{\"code\":\"BusinessRuleViolation\"}");

        ExecutionException error = assertThrows(ExecutionException.class,
                () -> api.completeMatch(42, new Completion(SiegeEndReason.TIME_EXPIRED, null, List.of(), List.of()))
                        .get(5, TimeUnit.SECONDS));
        ApiException api = assertInstanceOf(ApiException.class, error.getCause());
        assertEquals(409, api.getStatusCode());
    }

    @Test
    void networkErrors_keepTheIOExceptionAsCause_soRetryPolicyRetriesThem() throws Exception {
        int port = server.getAddress().getPort();
        server.stop(0);
        SiegeMatchesCommandApiImpl offline = new SiegeMatchesCommandApiImpl("http://127.0.0.1:" + port + "/api",
                new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build(), new ObjectMapper(), null, executor, false);

        ExecutionException error = assertThrows(ExecutionException.class,
                () -> offline.abortMatch(1, SiegeEndReason.SERVER_RESTART).get(5, TimeUnit.SECONDS));
        ApiException api = assertInstanceOf(ApiException.class, error.getCause());
        assertEquals(-1, api.getStatusCode());
        assertInstanceOf(IOException.class, api.getCause());
    }
}

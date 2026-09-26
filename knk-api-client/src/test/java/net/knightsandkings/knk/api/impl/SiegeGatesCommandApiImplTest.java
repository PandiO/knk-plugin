package net.knightsandkings.knk.api.impl;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeGateRecords.DoorState;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeGateRecords.LockdownEntry;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeGateRecords.RestoreResult;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeGateRecords.RestoredGate;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Siege Phase 7a: SiegeGatesCommandApiImpl against a local HTTP server (paths, bodies, mapping). */
class SiegeGatesCommandApiImplTest {

    private HttpServer server;
    private ExecutorService executor;
    private SiegeGatesCommandApiImpl api;
    private final Map<String, String> bodies = new ConcurrentHashMap<>();
    private final Map<String, String> responses = new ConcurrentHashMap<>();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api", exchange -> {
            String path = exchange.getRequestURI().getPath();
            bodies.put(path, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responses.getOrDefault(path, "[]").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        executor = Executors.newFixedThreadPool(2);
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .registerModule(new JavaTimeModule());
        api = new SiegeGatesCommandApiImpl("http://127.0.0.1:" + server.getAddress().getPort() + "/api",
                new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build(), mapper, null, executor, false);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void lockdown_postsEachGatesRoleAndDoors() throws Exception {
        api.lockdown(42, List.of(
                new LockdownEntry(400, true, false, false, List.of(new DoorState(4001, false, 500, false), new DoorState(4002, true, 250, false))),
                new LockdownEntry(401, false, true, true, List.of()))).get(5, TimeUnit.SECONDS);

        JsonNode body = json.readTree(bodies.get("/api/siege-matches/42/gate-lockdown"));
        JsonNode first = body.get("gates").get(0);
        assertEquals(400, first.get("gateStructureId").asInt());
        assertTrue(first.get("isObjectiveGate").asBoolean());
        assertEquals("CLOSED", first.get("doors").get(0).get("openedState").asText());
        assertEquals("OPEN", first.get("doors").get(1).get("openedState").asText());
        assertEquals(250.0, first.get("doors").get(1).get("healthCurrent").asDouble());
        assertTrue(body.get("gates").get(1).get("forcedOpen").asBoolean());
        assertTrue(body.get("gates").get(1).get("invincible").asBoolean());
    }

    @Test
    void restoreAndRestoreStale_mapTheSnapshots() throws Exception {
        String result = """
                {"restored":[{"siegeMatchId":42,"gateStructureId":400,"createdAt":"2026-09-26T20:00:00Z",
                  "snapshot":{"version":1,"structure":{"isInvincibleOverride":true},
                    "doors":[{"gateDoorId":4001,"openedState":"CLOSED","healthCurrent":500,"isDestroyed":false},
                             {"gateDoorId":4002,"openedState":"OPENING","healthCurrent":250,"isDestroyed":true}]}}],
                 "clearedGateStructureIds":[402]}""";
        responses.put("/api/siege-matches/42/gate-restore", result);
        responses.put("/api/siege-matches/restore-stale-gates", "{\"restored\":[],\"clearedGateStructureIds\":[]}");

        RestoreResult restored = api.restore(42).get(5, TimeUnit.SECONDS);
        RestoreResult stale = api.restoreStale().get(5, TimeUnit.SECONDS);

        assertEquals(List.of(new RestoredGate(42, 400, List.of(new DoorState(4001, false, 500, false), new DoorState(4002, true, 250, true)))),
                restored.restored());
        assertEquals(List.of(402), restored.clearedGateStructureIds());
        assertTrue(stale.isEmpty());
        assertTrue(bodies.containsKey("/api/siege-matches/restore-stale-gates"));
    }
}

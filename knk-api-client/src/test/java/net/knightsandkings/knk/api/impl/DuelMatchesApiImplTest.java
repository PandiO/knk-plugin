package net.knightsandkings.knk.api.impl;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import net.knightsandkings.knk.core.domain.duels.DuelRefusedException;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.Completion;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.FightStart;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.ItemSummary;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.LockRequest;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.MatchRecord;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.Result;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.Review;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.Settlement;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.SideTelemetry;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.Status;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.Telemetry;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.UndeliveredItems;
import net.knightsandkings.knk.core.duel.DuelEndReason;
import net.knightsandkings.knk.core.duel.GearMode;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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

/**
 * Arena duels (KNG-213): DuelMatchesApiImpl against a local HTTP server - paths, Idempotency-Key and
 * X-Acting-User-Id headers, JSON bodies (camelCase, the API's enum names) and the mapping of results and refusals.
 */
class DuelMatchesApiImplTest {

    private static final UUID TOKEN = UUID.fromString("6f1c2a5e-0d7b-4c39-9a51-2f0b7d5e9c11");

    private HttpServer server;
    private ExecutorService executor;
    private DuelMatchesApiImpl api;
    private final List<String> requests = new ArrayList<>();
    private final Map<String, String> bodies = new ConcurrentHashMap<>();
    private final Map<String, String> keys = new ConcurrentHashMap<>();
    private final Map<String, String> actors = new ConcurrentHashMap<>();
    private final Map<String, Integer> statusByPath = new ConcurrentHashMap<>();
    private final Map<String, String> responseByPath = new ConcurrentHashMap<>();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api", exchange -> {
            String path = exchange.getRequestURI().getPath();
            synchronized (requests) {
                requests.add(exchange.getRequestMethod() + " " + path);
            }
            bodies.put(path, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
            if (key != null) keys.put(path, key);
            String actor = exchange.getRequestHeaders().getFirst("X-Acting-User-Id");
            if (actor != null) actors.put(path, actor);
            int status = statusByPath.getOrDefault(path, 200);
            byte[] bytes = responseByPath.getOrDefault(path, "").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        executor = Executors.newFixedThreadPool(2);
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .registerModule(new JavaTimeModule());
        OkHttpClient http = new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build();
        api = new DuelMatchesApiImpl("http://127.0.0.1:" + server.getAddress().getPort() + "/api", http, mapper, null, executor, false);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        executor.shutdownNow();
    }

    private static <T> T await(CompletableFuture<T> f) throws Exception {
        return f.get(5, TimeUnit.SECONDS);
    }

    private static String match(String status, String review, String extra) {
        return "{\"id\":42,\"publicToken\":\"" + TOKEN + "\",\"arenaId\":7,\"challengerUserId\":1,\"opponentUserId\":2,"
                + "\"gearMode\":\"OWN_GEAR_DROP\",\"status\":\"" + status + "\",\"review\":\"" + review + "\",\"challengerCoins\":500,"
                + "\"opponentCoins\":400,\"feePercent\":5,\"valueMoving\":true,\"undeliveredItems\":\"None\","
                + "\"stakeItems\":[{\"ownerUserId\":1,\"material\":\"DIAMOND_SWORD\",\"amount\":1,\"name\":\"Edge\",\"instanceId\":99}],"
                + "\"createdAt\":\"2026-10-21T10:15:30.1234567Z\"" + extra + "}";
    }

    private static LockRequest lock() {
        return new LockRequest(TOKEN, 7, 1, 2, GearMode.OWN_GEAR_DROP, null, 500, 400,
                List.of(new ItemSummary(1, "DIAMOND_SWORD", 1, "Edge", "Epic", 99L)), 2, Instant.parse("2026-10-21T10:15:30Z"));
    }

    @Test
    void lock_postsTheContract_withTheLockKey_andMapsTheMatch() throws Exception {
        responseByPath.put("/api/DuelMatches", match("Created", "None", ""));
        statusByPath.put("/api/DuelMatches", 201);

        MatchRecord match = await(api.lock(lock()));

        assertEquals(List.of("POST /api/DuelMatches"), requests);
        assertEquals("duel-lock:" + TOKEN, keys.get("/api/DuelMatches"));
        JsonNode body = json.readTree(bodies.get("/api/DuelMatches"));
        assertEquals(TOKEN.toString(), body.get("publicToken").asText());
        assertEquals("OWN_GEAR_DROP", body.get("gearMode").asText());
        assertEquals(500, body.get("challengerCoins").asLong());
        assertEquals("DIAMOND_SWORD", body.get("stakeItems").get(0).get("material").asText());
        assertEquals("2026-10-21T10:15:30Z", body.get("lockedAt").asText());
        assertFalse(body.has("fixedKitId"));
        assertFalse(body.has("challengerGems"));

        assertEquals(42, match.id());
        assertEquals(TOKEN, match.token());
        assertEquals((Status.CREATED), match.status());
        assertEquals(GearMode.OWN_GEAR_DROP, match.gearMode());
        assertEquals(99L, match.stakeItems().get(0).instanceId());
        assertTrue(match.isOpen());
        assertEquals(Instant.parse("2026-10-21T10:15:30.1234567Z"), match.createdAt());
    }

    @Test
    void aRefusal_isADuelRefusedException_withTheApisCode() throws Exception {
        responseByPath.put("/api/DuelMatches", "{\"code\":\"PairCooldown\",\"message\":\"Wait 4 min before another staked duel together.\",\"details\":{\"minutes\":10}}");
        statusByPath.put("/api/DuelMatches", 422);

        ExecutionException ex = assertThrows(ExecutionException.class, () -> await(api.lock(lock())));

        DuelRefusedException refused = DuelRefusedException.find(ex);
        assertEquals("PairCooldown", refused.code());
        assertEquals(422, refused.getStatusCode());
        assertEquals("Wait 4 min before another staked duel together.", refused.playerMessage());
    }

    @Test
    void aServerError_staysARetryableApiException() {
        statusByPath.put("/api/DuelMatches/42/complete", 503);
        responseByPath.put("/api/DuelMatches/42/complete", "down");

        ExecutionException ex = assertThrows(ExecutionException.class, () ->
                await(api.complete(42, TOKEN, new Completion(1, false, null, List.of(), null))));

        ApiException api = assertInstanceOf(ApiException.class, ex.getCause());
        assertFalse(api instanceof DuelRefusedException);
        assertEquals(503, api.getStatusCode());
    }

    @Test
    void complete_sendsWinOrForfeitWithTelemetryAndSpoils_andMapsTheSettlement() throws Exception {
        responseByPath.put("/api/DuelMatches/42/complete",
                "{\"match\":" + match("Completed", "Held", ",\"winnerUserId\":1,\"reviewTier\":2,\"reviewSignals\":[\"S1\",\"S4\"]")
                        + ",\"settlement\":\"Held\",\"alreadyDone\":false}");
        var telemetry = new Telemetry(4.5, new SideTelemetry(20, 0, 5, 5, 10, "IRON_SWORD", true),
                new SideTelemetry(0, 20, 0, 0, 0, "AIR", false));

        Result result = await(api.complete(42, TOKEN, new Completion(1, true, telemetry,
                List.of(new ItemSummary(2, "IRON_HELMET", 1, null, null, null)), null)));

        assertEquals("duel-complete:" + TOKEN, keys.get("/api/DuelMatches/42/complete"));
        JsonNode body = json.readTree(bodies.get("/api/DuelMatches/42/complete"));
        assertEquals("Forfeit", body.get("endReason").asText());
        assertEquals(1, body.get("winnerUserId").asInt());
        assertEquals(4.5, body.get("telemetry").get("durationSeconds").asDouble());
        assertEquals(0, body.get("telemetry").get("opponent").get("attackAttempts").asInt());
        assertFalse(body.get("telemetry").get("opponent").get("hadWeapon").asBoolean());
        assertEquals("IRON_HELMET", body.get("spoilsItems").get(0).get("material").asText());
        assertFalse(body.get("spoilsItems").get(0).has("name"));

        assertEquals(Settlement.HELD, result.settlement());
        assertEquals(Review.HELD, result.match().review());
        assertEquals(List.of("S1", "S4"), result.match().reviewSignals());
        assertEquals(Integer.valueOf(1), result.match().winnerUserId());
    }

    @Test
    void startFight_cancel_abortUnfinished_useTheirPathsAndKeys() throws Exception {
        responseByPath.put("/api/DuelMatches/42/start-fight", match("InProgress", "None", ",\"battlegroundId\":60"));
        responseByPath.put("/api/DuelMatches/42/cancel", "{\"match\":" + match("Completed", "None", "") + ",\"settlement\":\"Refunded\",\"alreadyDone\":true}");
        responseByPath.put("/api/DuelMatches/abort-unfinished", "{\"abortedMatchIds\":[3,4]}");

        MatchRecord started = await(api.startFight(42, TOKEN, new FightStart(60, 7, 8, null)));
        Result cancelled = await(api.cancel(42, TOKEN, DuelEndReason.AGREED_DRAW, null));
        List<Integer> aborted = await(api.abortUnfinished(DuelEndReason.SERVER_RESTART));

        assertEquals(Status.IN_PROGRESS, started.status());
        assertEquals(Integer.valueOf(60), started.battlegroundId());
        assertEquals("duel-start:" + TOKEN, keys.get("/api/DuelMatches/42/start-fight"));
        assertEquals(7, json.readTree(bodies.get("/api/DuelMatches/42/start-fight")).get("challengerKitId").asInt());
        assertEquals("AgreedDraw", json.readTree(bodies.get("/api/DuelMatches/42/cancel")).get("endReason").asText());
        assertEquals("duel-cancel:" + TOKEN, keys.get("/api/DuelMatches/42/cancel"));
        assertEquals(Settlement.REFUNDED, cancelled.settlement());
        assertTrue(cancelled.alreadyDone());
        assertEquals("ServerRestart", json.readTree(bodies.get("/api/DuelMatches/abort-unfinished")).get("endReason").asText());
        assertEquals(List.of(3, 4), aborted);
        assertThrows(IllegalArgumentException.class, () -> api.cancel(42, TOKEN, DuelEndReason.WIN, null));
    }

    @Test
    void findByToken_isEmptyOn404() throws Exception {
        statusByPath.put("/api/DuelMatches/by-token/" + TOKEN, 404);
        responseByPath.put("/api/DuelMatches/by-token/" + TOKEN, "{\"code\":\"NotFound\",\"message\":\"No duel match has token x.\"}");

        assertTrue(await(api.findByToken(TOKEN)).isEmpty());

        statusByPath.put("/api/DuelMatches/by-token/" + TOKEN, 200);
        responseByPath.put("/api/DuelMatches/by-token/" + TOKEN, match("Completed", "Voided", ",\"undeliveredItems\":\"ReturnToOwners\""));
        MatchRecord found = await(api.findByToken(TOKEN)).orElseThrow();
        assertEquals(Review.VOIDED, found.review());
        assertEquals(UndeliveredItems.RETURN_TO_OWNERS, found.undeliveredItems());
    }

    @Test
    void staffActions_nameTheStaffMember() throws Exception {
        responseByPath.put("/api/DuelMatches/42/release", "{\"match\":" + match("Completed", "Released", "") + ",\"settlement\":\"Paid\"}");
        responseByPath.put("/api/DuelMatches/42/void", "{\"match\":" + match("Completed", "Voided", "") + ",\"settlement\":\"Refunded\"}");

        assertEquals(Settlement.PAID, await(api.release(42, 900, null)).settlement());
        assertEquals(Settlement.REFUNDED, await(api.voidHeld(42, 900, "alt account")).settlement());

        assertEquals("900", actors.get("/api/DuelMatches/42/release"));
        assertEquals("duel-void:42", keys.get("/api/DuelMatches/42/void"));
        assertEquals("alt account", json.readTree(bodies.get("/api/DuelMatches/42/void")).get("reason").asText());
        assertFalse(json.readTree(bodies.get("/api/DuelMatches/42/release")).has("reason"));
    }

    @Test
    void check_mapsTheRefusals() throws Exception {
        responseByPath.put("/api/DuelMatches/check", "{\"allowed\":false,\"valueMoving\":true,\"feeCoins\":45,\"feePercent\":5,"
                + "\"refusals\":[{\"code\":\"StakeRatioTooLow\",\"message\":\"The smaller coin stake must be at least 50%.\"}]}");

        var check = await(api.check(lock()));

        assertFalse(check.allowed());
        assertEquals(45, check.feeCoins());
        assertEquals("StakeRatioTooLow", check.refusals().get(0).code());
        assertNull(keys.get("/api/DuelMatches/check"));
    }
}

package net.knightsandkings.knk.api.impl;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import net.knightsandkings.knk.api.auth.NoAuthProvider;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.DoorRepairCheckpointDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.MatchCheckpointDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.MatchResultDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.PlayerCheckpointDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.PlayerResultDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.SettlementDto;
import net.knightsandkings.knk.core.exception.ApiException;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Siege Survival phase 5 (PandiO/knk-workspace#41, link S8a): the result and checkpoint calls — URLs, methods, ISO
 * dates in the body (the client mapper writes timestamps by default), the API-filled fields read back, the 409 codes.
 */
class SiegeSurvivalMatchResultApiImplTest {

    /** The API's answer to a stored team-wipe result (API-filled fields included). */
    static final String RESULT_JSON = """
            {"siegeMatchId":900,"siegeLobbyId":1,"siegeScenarioId":100,"difficultyProfileId":10,"configurationGeneratedAt":"2026-11-07T10:00:00Z",
             "startedAt":"2026-11-07T10:01:00Z","endedAt":"2026-11-07T10:31:00Z","endReason":"TeamWipe","reachedWave":10,"completedWave":9,
             "earnedKills":123,"systemKills":4,
             "players":[{"userId":1,"presentAtEnd":true,"leftAt":null,"kitId":70,"kills":10,"bonusKills":1,"deaths":1,"repairWork":12.5,
                         "matchCurrencyEarned":700,"matchCurrencySpent":1000,"finalMatchBalance":-300,"coinsAccrued":303.0,"xpAccrued":30.3,"coinsSettled":303,"xpSettled":30,
                         "mysteryBoxRolls":2,"mysteryBoxClaims":1,"mysteryBoxForfeits":1},
                        {"userId":5,"presentAtEnd":false,"leftAt":"2026-11-07T10:20:00Z","kitId":null,"kills":1,"bonusKills":0,"deaths":0,"repairWork":0,
                         "matchCurrencyEarned":70,"matchCurrencySpent":0,"finalMatchBalance":70,"coinsAccrued":30.0,"xpAccrued":3.0,"coinsSettled":0,"xpSettled":0,
                         "mysteryBoxRolls":0,"mysteryBoxClaims":0,"mysteryBoxForfeits":0}],
             "settlement":{"settlesRewards":true,"totalCoins":303,"totalXp":30,"ledgerKey":"siege-survival:900","settledAt":"2026-11-07T10:31:02Z","ledgerPostingId":"tx_abc","playersSettled":1},
             "equipmentMode":"Kits","rewardPolicyVersion":"v1-per-kill","source":"Plugin","recordedAt":"2026-11-07T10:31:02Z"}
            """;

    static final String CHECKPOINT_JSON = """
            {"siegeMatchId":900,"siegeScenarioId":100,"difficultyProfileId":10,"capturedAt":"2026-11-07T10:20:00Z","substate":"WaveActive","wave":6,"completedWave":5,
             "specialProfileKey":null,"aliveEnemies":7,"queuedEnemies":12,"npcHeldObjectiveIds":[502],"activeSpawnZoneIds":[30,32],
             "doorRepairs":[{"gateDoorId":1,"destroyed":true,"repairWork":3.5,"repairTarget":10,"repairers":2}],
             "players":[{"userId":1,"ghost":false,"kitId":70,"matchBalance":1500,"kills":10,"deaths":1,"pendingMysteryBoxRollId":"r1"}],
             "updatedAt":"2026-11-07T10:20:01Z","revision":2}
            """;

    private final List<Request> seen = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private String responseJson = "{}";
    private int responseCode = 200;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
        Request request = chain.request();
        seen.add(request);
        bodies.add(bodyOf(request));
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(responseCode).message("status " + responseCode)
                .body(ResponseBody.create(responseJson, MediaType.get("application/json"))).build();
    }).build();
    // As KnkApiClient builds it: JavaTimeModule registered, timestamps NOT disabled (the impl must still send ISO strings).
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .registerModule(new JavaTimeModule());
    private final SiegeSurvivalMatchApiImpl api = new SiegeSurvivalMatchApiImpl("http://api.test/api", client,
            objectMapper, new NoAuthProvider(), executor, false);

    private static String bodyOf(Request request) throws IOException {
        if (request.body() == null) return null;
        Buffer buffer = new Buffer();
        request.body().writeTo(buffer);
        return buffer.readUtf8();
    }

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    private static MatchResultDto teamWipe() {
        OffsetDateTime t0 = OffsetDateTime.of(2026, 11, 7, 10, 1, 0, 0, ZoneOffset.UTC);
        return new MatchResultDto(900, 1, 100, 10, t0.minusMinutes(1), t0, t0.plusMinutes(30), SiegeSurvivalMatchApiImpl.END_TEAM_WIPE, 10, 9, 123, 4,
                List.of(new PlayerResultDto(1, true, null, 70, 10, 1, 1, 12.5, 700L, 1000L, -300L, new BigDecimal("303.0"), new BigDecimal("30.3"), 303, 30, null, null, null),
                        new PlayerResultDto(5, false, t0.plusMinutes(19), null, 1, 0, 0, 0.0, 70L, 0L, 70L, new BigDecimal("30.0"), new BigDecimal("3.0"), 0, 0, null, null, null)),
                new SettlementDto(true, 303, 30, SiegeSurvivalMatchApiImpl.ledgerKey(900), null, null, null),
                "Kits", null, null, null);
    }

    @Test
    void postResult_PostsIsoDates_AndReadsTheStoredResultWithTheApiFilledFields() {
        responseCode = 201;
        responseJson = RESULT_JSON;

        MatchResultDto stored = api.postResult(900, teamWipe()).join();

        assertEquals("POST", seen.get(0).method());
        assertEquals("http://api.test/api/siege-survival/matches/900/result", seen.get(0).url().toString());
        String body = bodies.get(0);
        assertTrue(body.contains("\"endReason\":\"TeamWipe\""), body);
        assertTrue(body.contains("\"startedAt\":\"2026-11-07T10:01:00Z\""), body);       // ISO, not a numeric timestamp
        assertTrue(body.contains("\"leftAt\":\"2026-11-07T10:20:00Z\""), body);
        assertTrue(body.contains("\"coinsAccrued\":303.0") && body.contains("\"xpAccrued\":30.3"), body);
        assertTrue(body.contains("\"settlement\":{\"settlesRewards\":true,\"totalCoins\":303,\"totalXp\":30,\"ledgerKey\":\"siege-survival:900\""), body);
        assertTrue(body.contains("\"equipmentMode\":\"Kits\""), body);
        assertFalse(body.matches("(?s).*\"startedAt\":\\d.*"), body);

        assertEquals(900, stored.siegeMatchId());
        assertEquals("v1-per-kill", stored.rewardPolicyVersion());
        assertEquals("Plugin", stored.source());
        assertEquals(2026, stored.recordedAt().getYear());
        assertEquals("Kits", stored.equipmentMode());
        assertEquals("tx_abc", stored.settlement().ledgerPostingId());
        assertEquals(1, stored.settlement().playersSettled());
        assertEquals(31, stored.settlement().settledAt().getMinute());
        PlayerResultDto p1 = stored.players().get(0);
        assertEquals(new BigDecimal("30.3"), p1.xpAccrued());
        assertEquals(303, p1.coinsSettled());
        assertEquals(List.of(2, 1, 1), List.of(p1.mysteryBoxRolls(), p1.mysteryBoxClaims(), p1.mysteryBoxForfeits()));
        assertFalse(stored.players().get(1).presentAtEnd());

        // A retry answers 200 with the stored result: the same to the caller.
        responseCode = 200;
        assertEquals("tx_abc", api.postResult(900, teamWipe()).join().settlement().ledgerPostingId());
    }

    @Test
    void getResult_GetsTheResultUrl() {
        responseJson = RESULT_JSON;
        MatchResultDto result = api.getResult(900).join();
        assertEquals("GET", seen.get(0).method());
        assertEquals("http://api.test/api/siege-survival/matches/900/result", seen.get(0).url().toString());
        assertNull(bodies.get(0));
        assertEquals(10, result.reachedWave());
        assertEquals("siege-survival:900", result.settlement().ledgerKey());
    }

    @Test
    void putAndGetCheckpoint_UseTheCheckpointUrl_WithIsoDates() {
        responseCode = 201;
        responseJson = CHECKPOINT_JSON;
        OffsetDateTime at = OffsetDateTime.of(2026, 11, 7, 10, 20, 0, 0, ZoneOffset.UTC);
        MatchCheckpointDto checkpoint = new MatchCheckpointDto(900, 100, 10, at, "WaveActive", 6, 5, null, 7, 12, List.of(502), List.of(30, 32),
                List.of(new DoorRepairCheckpointDto(1, true, 3.5, 10.0, 2)),
                List.of(new PlayerCheckpointDto(1, false, 70, 1500L, 10, 1, "r1")), null, null);

        MatchCheckpointDto stored = api.putCheckpoint(900, checkpoint).join();

        assertEquals("PUT", seen.get(0).method());
        assertEquals("http://api.test/api/siege-survival/matches/900/checkpoint", seen.get(0).url().toString());
        assertTrue(bodies.get(0).contains("\"capturedAt\":\"2026-11-07T10:20:00Z\""), bodies.get(0));
        assertTrue(bodies.get(0).contains("\"pendingMysteryBoxRollId\":\"r1\""), bodies.get(0));
        assertEquals(2, stored.revision());
        assertEquals(2026, stored.updatedAt().getYear());
        assertEquals(List.of(30, 32), stored.activeSpawnZoneIds());

        responseCode = 200;
        MatchCheckpointDto read = api.getCheckpoint(900).join();
        assertEquals("GET", seen.get(1).method());
        assertEquals("http://api.test/api/siege-survival/matches/900/checkpoint", seen.get(1).url().toString());
        assertEquals(6, read.wave());
        assertTrue(read.doorRepairs().get(0).destroyed());
    }

    @Test
    void notFound_IsNull_AndConflictsCarryTheirCode() {
        responseCode = 404;
        responseJson = "{\"code\":\"NotFound\",\"message\":\"Siege match 12345 not found.\"}";
        assertNull(api.postResult(12345, teamWipe()).join());
        assertNull(api.getResult(12345).join());
        assertNull(api.getCheckpoint(12345).join());

        responseCode = 409;
        responseJson = "{\"code\":\"ResultAlreadyStored\",\"message\":\"Siege match 900 already has a stored result\"}";
        CompletionException stored = assertThrows(CompletionException.class, () -> api.postResult(900, teamWipe()).join());
        assertEquals(SiegeSurvivalMatchApiImpl.RESULT_ALREADY_STORED, api.conflictCode((ApiException) stored.getCause()));

        responseJson = "{\"code\":\"SettlementMismatch\",\"message\":\"user 1: settled 304 coins / 30 XP, recomputed 303 / 30\"}";
        CompletionException mismatch = assertThrows(CompletionException.class, () -> api.postResult(900, teamWipe()).join());
        assertEquals(SiegeSurvivalMatchApiImpl.SETTLEMENT_MISMATCH, api.conflictCode((ApiException) mismatch.getCause()));

        responseJson = "{\"code\":\"MatchAborted\",\"message\":\"aborted\"}";
        CompletionException aborted = assertThrows(CompletionException.class, () -> api.postResult(900, teamWipe()).join());
        assertEquals(SiegeSurvivalMatchApiImpl.MATCH_ABORTED, api.conflictCode((ApiException) aborted.getCause()));
    }

    @Test
    void settlementRules_MirrorTheApi() {
        assertEquals("siege-survival:41", SiegeSurvivalMatchApiImpl.ledgerKey(41));
        assertTrue(SiegeSurvivalMatchApiImpl.settlesRewards(SiegeSurvivalMatchApiImpl.END_TEAM_WIPE));
        assertTrue(SiegeSurvivalMatchApiImpl.settlesRewards(SiegeSurvivalMatchApiImpl.END_INSTA_LOSE_OBJECTIVE_CAPTURED));
        assertTrue(SiegeSurvivalMatchApiImpl.settlesRewards(SiegeSurvivalMatchApiImpl.END_ALL_OBJECTIVES_CAPTURED));
        assertFalse(SiegeSurvivalMatchApiImpl.settlesRewards(SiegeSurvivalMatchApiImpl.END_NO_PLAYERS_LEFT));
        assertFalse(SiegeSurvivalMatchApiImpl.settlesRewards(SiegeSurvivalMatchApiImpl.END_ADMIN_ABORT));
        assertFalse(SiegeSurvivalMatchApiImpl.settlesRewards(SiegeSurvivalMatchApiImpl.END_SERVER_RESTART));
        assertFalse(SiegeSurvivalMatchApiImpl.settlesRewards(SiegeSurvivalMatchApiImpl.END_TECHNICAL_LIMIT));
        assertFalse(SiegeSurvivalMatchApiImpl.settlesRewards(null));
    }
}

package net.knightsandkings.knk.api.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.auth.NoAuthProvider;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.MysteryBoxRollDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtosTest;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Siege Survival phase 4 (PandiO/knk-workspace#41, link S7a): the match-scoped Mystery Box calls, their URLs, bodies and answers. */
class SiegeSurvivalMatchApiImplTest {

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
    private final ObjectMapper objectMapper = new ObjectMapper();
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

    @Test
    void roll_PostsTheIdempotentBody_AndReadsTheRoll() {
        responseCode = 201;
        responseJson = SiegeSurvivalDtosTest.ROLL_JSON;

        MysteryBoxRollDto roll = api.roll(900, 50, "match-900-box-50-7", 1, 1000).join();

        assertEquals("POST", seen.get(0).method());
        assertEquals("http://api.test/api/siege-survival/matches/900/mystery-box/50/roll", seen.get(0).url().toString());
        assertEquals("{\"rollId\":\"match-900-box-50-7\",\"userId\":1,\"priceCharged\":1000}", bodies.get(0));
        assertEquals("match-900-box-50-7", roll.rollId());
        assertEquals(401, roll.entry().id());
        assertEquals(List.of(402, 401, 401), roll.reel());
        assertEquals("Rolled", roll.state());

        // The same rollId answers 200 with the stored roll: the client treats it the same.
        responseCode = 200;
        assertEquals(401, api.roll(900, 50, "match-900-box-50-7", 1, 1000).join().entry().id());
    }

    @Test
    void claim_AndForfeit_PostToTheRollUrl_WithTheEnumString() {
        responseJson = SiegeSurvivalDtosTest.ROLL_JSON.replace("\"state\":\"Rolled\"", "\"state\":\"Claimed\"")
                .replace("\"claimedAt\":null", "\"claimedAt\":\"2026-11-07T10:00:05Z\"").replace("\"deliveryKind\":null", "\"deliveryKind\":\"Drop\"");
        MysteryBoxRollDto claimed = api.claim(900, "match-900-box-50-7", SiegeSurvivalMatchApiImpl.DELIVERY_DROP).join();
        assertEquals("http://api.test/api/siege-survival/matches/900/mystery-box/rolls/match-900-box-50-7/claim", seen.get(0).url().toString());
        assertEquals("{\"deliveryKind\":\"Drop\"}", bodies.get(0));
        assertEquals("Claimed", claimed.state());
        assertEquals("Drop", claimed.deliveryKind());
        assertEquals(2026, claimed.claimedAt().getYear());

        responseJson = SiegeSurvivalDtosTest.ROLL_JSON.replace("\"state\":\"Rolled\"", "\"state\":\"Forfeited\"")
                .replace("\"forfeitReason\":null", "\"forfeitReason\":\"RollerDied\"");
        MysteryBoxRollDto forfeited = api.forfeit(900, "a b/c", SiegeSurvivalMatchApiImpl.FORFEIT_ROLLER_DIED).join();
        assertEquals("http://api.test/api/siege-survival/matches/900/mystery-box/rolls/a+b%2Fc/forfeit", seen.get(1).url().toString());
        assertEquals("{\"reason\":\"RollerDied\"}", bodies.get(1));
        assertEquals("Forfeited", forfeited.state());
        assertEquals("RollerDied", forfeited.forfeitReason());
    }

    @Test
    void listRolls_GetsTheMatchsRolls() {
        responseJson = "[" + SiegeSurvivalDtosTest.ROLL_JSON + "]";
        List<MysteryBoxRollDto> rolls = api.listRolls(900).join();
        assertEquals("GET", seen.get(0).method());
        assertEquals("http://api.test/api/siege-survival/matches/900/mystery-box/rolls", seen.get(0).url().toString());
        assertEquals(1, rolls.size());
        assertEquals(1000, rolls.get(0).priceCharged());
    }

    @Test
    void notFound_IsNull_AndConflictsCarryTheirCode() {
        responseCode = 404;
        responseJson = "{\"code\":\"NotFound\",\"message\":\"User 2 is not a participant of siege match 900.\"}";
        assertNull(api.roll(900, 50, "r", 2, 1000).join());
        assertNull(api.claim(900, "nope", SiegeSurvivalMatchApiImpl.DELIVERY_INVENTORY).join());
        assertNull(api.forfeit(900, "nope", SiegeSurvivalMatchApiImpl.FORFEIT_TIMEOUT).join());
        assertNull(api.listRolls(12345).join());

        responseCode = 409;
        responseJson = "{\"code\":\"RollIdReused\",\"message\":\"Roll id 'r' was already used\"}";
        CompletionException reused = assertThrows(CompletionException.class, () -> api.roll(900, 50, "r", 1, 999).join());
        ApiException cause = (ApiException) reused.getCause();
        assertEquals(409, cause.getStatusCode());
        assertEquals(SiegeSurvivalMatchApiImpl.ROLL_ID_REUSED, api.conflictCode(cause));

        responseJson = "{\"code\":\"RollForfeited\",\"message\":\"forfeited\"}";
        CompletionException forfeited = assertThrows(CompletionException.class, () -> api.claim(900, "r", SiegeSurvivalMatchApiImpl.DELIVERY_INVENTORY).join());
        assertEquals(SiegeSurvivalMatchApiImpl.ROLL_FORFEITED, api.conflictCode((ApiException) forfeited.getCause()));

        responseCode = 500;
        responseJson = "{\"error\":\"boom\"}";
        CompletionException other = assertThrows(CompletionException.class, () -> api.roll(900, 50, "r", 1, 1000).join());
        assertTrue(other.getCause() instanceof ApiException);
        assertNull(api.conflictCode((ApiException) other.getCause()));
        assertNull(api.conflictCode(null));
    }
}

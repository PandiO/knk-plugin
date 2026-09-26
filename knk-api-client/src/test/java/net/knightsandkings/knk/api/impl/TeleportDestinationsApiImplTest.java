package net.knightsandkings.knk.api.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.NoAuthProvider;
import net.knightsandkings.knk.core.domain.teleport.KnkTeleportDestination;
import net.knightsandkings.knk.core.domain.teleport.TeleportChargeResult;
import net.knightsandkings.knk.core.domain.teleport.TeleportRefundResult;
import okio.Buffer;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Teleport Phase 5: JSON matches knk-web-api's Dtos/TeleportDtos.cs; refusals vs. no answer. */
class TeleportDestinationsApiImplTest {

    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
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
                .body(ResponseBody.create(responseJson, MediaType.get("application/json"))).build();
    }).build();

    private final TeleportDestinationsApiImpl api = new TeleportDestinationsApiImpl("http://api.test/api", client, mapper,
            new NoAuthProvider(), executor, false);

    private static final String KARDENNA = "{\"domainId\":3,\"name\":\"Kardenna\",\"domainType\":\"Town\","
        + "\"location\":{\"world\":\"world\",\"x\":10.5,\"y\":64,\"z\":-20.5,\"yaw\":90,\"pitch\":0},"
        + "\"priceGems\":10,\"minTitleName\":\"Knight\",\"minPremiumTierName\":null,\"requiresDiscovery\":true,"
        + "\"available\":false,\"requirementsMet\":false,\"canAfford\":true,\"lockCode\":\"TitleTooLow\","
        + "\"lockReason\":\"Reach title Knight to unlock\"}";

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void listGetsThePlayersDestinationsAndMapsEveryField() {
        responseJson = "[" + KARDENNA + ",{\"domainId\":4,\"name\":\"NoLocation\"}]";

        List<KnkTeleportDestination> list = api.listForUser(12).join();

        assertEquals("GET", seen.get(0).method());
        assertEquals("http://api.test/api/teleport-destinations?userId=12", seen.get(0).url().toString());
        assertEquals(1, list.size(), "a row without a location is dropped");
        KnkTeleportDestination d = list.get(0);
        assertEquals(3, d.domainId());
        assertEquals("Town", d.domainType());
        assertEquals("world", d.world());
        assertEquals(10.5, d.x());
        assertEquals(-20.5, d.z());
        assertEquals(90f, d.yaw());
        assertEquals(10, d.priceGems());
        assertEquals("Knight", d.minTitleName());
        assertTrue(d.requiresDiscovery());
        assertFalse(d.available());
        assertTrue(d.canAfford());
        assertEquals("TitleTooLow", d.lockCode());
        assertEquals("Reach title Knight to unlock", d.lockReason());
    }

    @Test
    void chargeWarpPostsTheKeyAndBypassFlags_AndMapsTheCharge() throws Exception {
        responseJson = "{\"currency\":\"Gems\",\"charged\":10,\"newBalance\":40,\"replayed\":true,"
            + "\"transactionPublicId\":\"01ABC\",\"destination\":" + KARDENNA + "}";

        TeleportChargeResult result = api.chargeWarp(3, 12, "warp:k1", true, false).join();

        assertEquals("POST", seen.get(0).method());
        assertEquals("http://api.test/api/teleport-destinations/3/charge", seen.get(0).url().toString());
        JsonNode body = mapper.readTree(bodies.get(0));
        assertEquals(12, body.get("userId").asInt());
        assertEquals("warp:k1", body.get("idempotencyKey").asText());
        assertTrue(body.get("bypassRequirements").asBoolean());
        assertFalse(body.get("bypassCost").asBoolean());
        assertTrue(result.allowed());
        assertEquals("Gems", result.currency());
        assertEquals(10, result.charged());
        assertEquals(40, result.newBalance());
        assertTrue(result.replayed());
        assertEquals(-20.5, result.destination().z());
    }

    @Test
    void a409IsARefusalWithTheServersCodeAndMessage() {
        status = 409;
        responseJson = "{\"error\":\"InsufficientGems\",\"message\":\"You don't have enough gems to teleport to this location!\"}";

        TeleportChargeResult result = api.chargeWarp(3, 12, "warp:k1", false, false).join();

        assertFalse(result.allowed());
        assertEquals("InsufficientGems", result.refusalCode());
        assertEquals("You don't have enough gems to teleport to this location!", result.refusalMessage());
    }

    @Test
    void a5xxIsNoAnswer_SoTheCallerCanRetry() {
        status = 503;
        responseJson = "{}";

        assertThrows(CompletionException.class, () -> api.chargeWarp(3, 12, "warp:k1", false, false).join());
    }

    @Test
    void requestFeeAndRefundBodies() throws Exception {
        responseJson = "{\"currency\":\"Coins\",\"charged\":100,\"newBalance\":900,\"replayed\":false,\"destination\":null}";
        TeleportChargeResult fee = api.chargeRequestFee(12, 100, "tpa:k", 13).join();
        responseJson = "{\"refunded\":true,\"currency\":\"Coins\",\"amount\":100,\"newBalance\":1000,\"replayed\":false}";
        TeleportRefundResult refund = api.refund(12, "tpa:k", "blocked").join();
        responseJson = "{\"refunded\":false,\"currency\":null,\"amount\":0,\"newBalance\":null,\"replayed\":false}";
        TeleportRefundResult nothing = api.refund(12, "tpa:other", null).join();

        assertEquals("http://api.test/api/teleport-destinations/request-fee", seen.get(0).url().toString());
        JsonNode feeBody = mapper.readTree(bodies.get(0));
        assertEquals(100, feeBody.get("amountCoins").asInt());
        assertEquals(13, feeBody.get("otherUserId").asInt());
        assertEquals("Coins", fee.currency());
        assertNull(fee.destination());

        assertEquals("http://api.test/api/teleport-destinations/refund", seen.get(1).url().toString());
        JsonNode refundBody = mapper.readTree(bodies.get(1));
        assertEquals("tpa:k", refundBody.get("idempotencyKey").asText());
        assertEquals("blocked", refundBody.get("reason").asText());
        assertTrue(refund.refunded());
        assertEquals(1000L, refund.newBalance());
        assertFalse(mapper.readTree(bodies.get(2)).has("reason"), "no reason field when there is none");
        assertFalse(nothing.refunded());
        assertNull(nothing.newBalance());
    }
}

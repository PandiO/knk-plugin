package net.knightsandkings.knk.api.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.NoAuthProvider;
import net.knightsandkings.knk.core.domain.analytics.WorldAnalyticsBatch;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.WorldAnalyticsApi;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

/**
 * KNG-34 link 7: the batch JSON matches knk-web-api's Dtos/WorldAnalyticsDtos.cs (and carries no player
 * identity); the answer is mapped; a refused call carries the ApiException with its status.
 */
class WorldAnalyticsApiImplTest {

    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final List<Request> seen = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private int status = 200;
    private String responseJson = "{}";

    private final OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
        Request request = chain.request();
        seen.add(request);
        Buffer buffer = new Buffer();
        request.body().writeTo(buffer);
        bodies.add(buffer.readUtf8());
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("x")
                .body(ResponseBody.create(responseJson, MediaType.get("application/json"))).build();
    }).build();

    private final WorldAnalyticsApiImpl api = new WorldAnalyticsApiImpl("http://api.test/api", client, mapper, new NoAuthProvider(), executor, false);

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private static WorldAnalyticsBatch batch() {
        return new WorldAnalyticsBatch(UUID.fromString("00000000-0000-0000-0000-00000000000a"), Instant.parse("2026-10-03T10:00:00Z"),
            List.of(new WorldAnalyticsBatch.MovementCell("world", 16, -1, 2, 5)),
            List.of(new WorldAnalyticsBatch.MenuStep("profile.main", "action:menu.open", "succeeded", 3)),
            List.of(new WorldAnalyticsBatch.DomainInteraction(null, "aldmoor", "enter", 4, 2),
                new WorldAnalyticsBatch.DomainInteraction(7, null, "discover", 1, 1)));
    }

    @Test
    void postBatch_sendsTheWireShape_andReadsTheResult() throws Exception {
        responseJson = "{\"batchId\":\"x\",\"duplicate\":false,\"day\":\"2026-10-03\",\"accepted\":3,"
            + "\"rejected\":[{\"section\":\"domainInteractions\",\"index\":0,\"code\":\"UnknownRegion\"}]}";

        WorldAnalyticsApi.BatchResult result = api.postBatch(batch(), "paper 25565").join();

        assertEquals(new WorldAnalyticsApi.BatchResult(false, 3, 1), result);
        assertEquals("http://api.test/api/world-analytics/batches", seen.get(0).url().toString());
        JsonNode body = mapper.readTree(bodies.get(0));
        assertEquals("00000000-0000-0000-0000-00000000000a", body.get("batchId").asText());
        assertEquals("paper 25565", body.get("serverName").asText());
        assertEquals("2026-10-03T10:00:00Z", body.get("windowStart").asText());
        JsonNode cell = body.get("movementCells").get(0);
        assertEquals(List.of("world", "16", "-1", "2", "5"), List.of(cell.get("world").asText(), cell.get("cellSize").asText(),
            cell.get("cellX").asText(), cell.get("cellZ").asText(), cell.get("samples").asText()));
        JsonNode step = body.get("menuSteps").get(0);
        assertEquals("action:menu.open", step.get("step").asText());
        assertEquals("succeeded", step.get("outcome").asText());
        JsonNode region = body.get("domainInteractions").get(0);
        assertEquals("aldmoor", region.get("regionId").asText());
        assertFalse(region.has("domainId"), "nulls are omitted");
        assertEquals(7, body.get("domainInteractions").get(1).get("domainId").asInt());
        assertEquals(2, region.get("uniquePlayers").asInt());

        for (Iterator<String> names = body.fieldNames(); names.hasNext(); ) {
            String name = names.next().toLowerCase();
            assertFalse(name.contains("user") || name.contains("uuid") || name.contains("player"), name);
        }
    }

    @Test
    void aRefusedBatch_failsWithTheStatus() {
        status = 400;
        responseJson = "{\"error\":\"InvalidBatch\",\"message\":\"windowStart is older than 7 days.\"}";

        CompletionException failure = assertThrows(CompletionException.class, () -> api.postBatch(batch(), "s").join());

        Throwable cause = failure.getCause();
        while (cause != null && !(cause instanceof ApiException)) {
            cause = cause.getCause();
        }
        assertInstanceOf(ApiException.class, cause);
        assertEquals(400, ((ApiException) cause).getStatusCode());
        assertTrue(seen.get(0).method().equals("POST"));
    }
}

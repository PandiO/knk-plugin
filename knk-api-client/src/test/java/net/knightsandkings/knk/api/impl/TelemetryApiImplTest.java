package net.knightsandkings.knk.api.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import net.knightsandkings.knk.core.ports.api.TelemetryApi;
import net.knightsandkings.knk.core.telemetry.TelemetryClientConfig;
import net.knightsandkings.knk.core.telemetry.TelemetryCorrelation;
import net.knightsandkings.knk.core.telemetry.TelemetryEvent;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

/**
 * KNG-34 link 6: request/response JSON matches knk-web-api's Dtos/TelemetryDtos.cs; the action's
 * correlation id travels as X-Correlation-Id; failed calls (except telemetry's own) reach the
 * api.call_failed observer with a value-free route template.
 */
class TelemetryApiImplTest {

    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final List<Request> seen = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<String> failures = new ArrayList<>();
    private int status = 200;
    private String responseJson = "{}";
    private boolean ioFailure;

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
        if (ioFailure) {
            throw new IOException("connection refused");
        }
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("x")
                .body(ResponseBody.create(responseJson, MediaType.get("application/json"))).build();
    }).build();

    private final TelemetryApiImpl api = new TelemetryApiImpl("http://api.test/api", client, mapper, new NoAuthProvider(), executor, false);

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        BaseApiImpl.setFailureObserver(null);
    }

    private static TelemetryEvent event() {
        return new TelemetryEvent(UUID.fromString("00000000-0000-0000-0000-00000000000a"), "siege.match_join", 1,
            Instant.parse("2026-10-03T10:00:00Z"), "paper 25565", 42, "1.2.3", TelemetryEvent.Level.BASELINE, 7,
            UUID.fromString("00000000-0000-0000-0000-00000000000b"), 3, 12, "corr-1", "siege", "match_join",
            TelemetryEvent.Outcome.SUCCEEDED, null, "siege_lobby", "4", Map.of("teamId", 2));
    }

    @Test
    void postBatch_sendsTheEnvelope_andReadsTheCounts() throws Exception {
        responseJson = "{\"accepted\":1,\"duplicates\":2,\"dropped\":3,\"rejected\":[{\"index\":0,\"code\":\"UnknownEvent\"}]}";

        TelemetryApi.BatchResult result = api.postBatch(List.of(event())).join();

        assertEquals(new TelemetryApi.BatchResult(1, 2, 3, 1), result);
        assertEquals("http://api.test/api/telemetry/events/batch", seen.get(0).url().toString());
        JsonNode e = mapper.readTree(bodies.get(0)).get(0);
        assertEquals("00000000-0000-0000-0000-00000000000a", e.get("eventId").asText());
        assertEquals("2026-10-03T10:00:00Z", e.get("occurredAt").asText());
        assertEquals(("baseline"), e.get("level").asText());
        assertEquals("succeeded", e.get("outcome").asText());
        assertEquals(42, e.get("serverSeq").asLong());
        assertEquals(12, e.get("matchId").asInt());
        assertEquals(2, e.get("payload").get("teamId").asInt());
        assertFalse(e.has("reasonCode"), "nulls are omitted");
    }

    @Test
    void getConfig_mapsTheEmitterConfig() {
        responseJson = "{\"enabled\":true,\"enhancedUserIds\":[7],\"activeTestRunIds\":[3,1],\"enhancedTestRunIds\":[3],"
            + "\"baselineEventNames\":[\"session.join\"],\"enhancedEventNames\":[\"movement.sample\"]}";

        TelemetryClientConfig config = api.getConfig().join();

        assertEquals(Set.of(7), config.enhancedUserIds());
        assertEquals(3, config.currentTestRunId());
        assertTrue(config.isEnhanced(99));
        assertEquals(Set.of("session.join"), config.baselineEventNames());
        assertEquals("http://api.test/api/telemetry/config", seen.get(0).url().toString());
    }

    @Test
    void correlationId_isSentWhenAnActionIsCorrelated() {
        api.getConfig().exceptionally(e -> null).join();
        TelemetryCorrelation.run("act-9", () -> api.getConfig().exceptionally(e -> null).join());

        assertNull(seen.get(0).header(TelemetryCorrelation.HEADER));
        // The executor in this test is not the propagating one: the header is read on the worker
        // thread, so correlate there as KnkApiClient's wrapped pool does.
        assertNull(seen.get(1).header(TelemetryCorrelation.HEADER));
        ExecutorService wrapped = net.knightsandkings.knk.api.client.KnkApiClientTestAccess.wrap(executor);
        TelemetryApiImpl correlated = new TelemetryApiImpl("http://api.test/api", client, mapper, new NoAuthProvider(), wrapped, false);
        TelemetryCorrelation.run("act-9", () -> correlated.getConfig().exceptionally(e -> null).join());
        assertEquals("act-9", seen.get(2).header(TelemetryCorrelation.HEADER));
    }

    @Test
    void failedCalls_reachTheObserver_butTelemetryCallsNever() {
        BaseApiImpl.setFailureObserver((method, route, code, exceptionType, correlation) ->
            failures.add(method + " " + route + " " + code + " " + exceptionType + " " + correlation));
        StatisticsApiImpl statistics = new StatisticsApiImpl("http://api.test/api", client, mapper, new NoAuthProvider(), executor, false);
        status = 503;

        assertThrows(CompletionException.class, () -> statistics.getVisibility(42, 42).join());
        assertThrows(CompletionException.class, () -> api.getConfig().join());
        ioFailure = true;
        assertThrows(CompletionException.class, () -> statistics.getCatalog().join());
        assertThrows(CompletionException.class, () -> api.postBatch(List.of(event())).join());

        assertEquals(List.of("GET /api/statistics/users/{id}/visibility 503 null null",
            "GET /api/statistics/catalog 0 IOException null"), failures);
    }
}

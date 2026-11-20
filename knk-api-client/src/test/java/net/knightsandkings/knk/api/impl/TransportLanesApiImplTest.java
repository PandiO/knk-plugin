package net.knightsandkings.knk.api.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.ApiKeyAuthProvider;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.TransportLanesApi;
import net.knightsandkings.knk.core.production.transport.LaneDue;
import net.knightsandkings.knk.core.production.transport.LaneMeasurement;
import net.knightsandkings.knk.core.production.transport.RoadConnection;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

/** PandiO/knk-workspace#681: the lane list, the report and the road connection (contract of #79, comment on #681). */
class TransportLanesApiImplTest {

    private static final String DUE = """
            [{"fromStructureId":4,"toStructureId":9,"fromStructureName":"North logger","toStructureName":"Docks",
              "from":{"world":"world","x":0,"y":65,"z":-10},"to":{"world":"world","x":100.5,"y":65,"z":110},
              "reason":"Missing","lastMeasuredAt":null},
             {"fromStructureId":5,"toStructureId":9,"fromName":"Bare quarry","toName":"Docks","from":null,
              "to":{"world":"world","x":100,"y":65,"z":110},"reason":"Stale","lastMeasuredAt":"2026-11-19T10:00:00"},
             {"fromStructureId":0,"toStructureId":9}]
            """;

    private final List<Request> seen = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private volatile int status = 200;
    private volatile String body = DUE;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
        seen.add(chain.request());
        if (chain.request().body() != null) {
            Buffer buffer = new Buffer();
            chain.request().body().writeTo(buffer);
            bodies.add(buffer.readUtf8());
        }
        return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("x")
                .body(ResponseBody.create(body, MediaType.get("application/json"))).build();
    }).build();
    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final TransportLanesApiImpl api = new TransportLanesApiImpl("http://api.test/api", client, mapper,
            new ApiKeyAuthProvider("secret"), executor, false);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void due_readsThePairsWithPositionsAndNames_andDropsRowsWithoutBothIds() {
        List<LaneDue> due = api.due(100).join();

        assertEquals("GET", seen.get(0).method());
        assertEquals("http://api.test/api/TransportLanes/due?limit=100", seen.get(0).url().toString());
        assertEquals("secret", seen.get(0).header("X-API-Key"));
        assertEquals(2, due.size());
        LaneDue first = due.get(0);
        assertEquals(4, first.fromStructureId());
        assertEquals(9, first.toStructureId());
        assertEquals("North logger", first.fromName());
        assertEquals(100.5, first.to().x());
        assertEquals("world", first.from().world());
        assertEquals("Missing", first.reason());
        LaneDue second = due.get(1);
        assertEquals("Bare quarry", second.fromName(), "fromName is read when fromStructureName is absent");
        assertNull(second.from(), "a structure without a Location has no position");
        assertEquals(Instant.parse("2026-11-19T10:00:00Z"), second.lastMeasuredAt());
    }

    @Test
    void report_sendsReachableLanesWithTheirRoadDistance_andUnreachableOnesWithout() throws Exception {
        body = "{\"created\":1,\"updated\":1}";
        Instant at = Instant.parse("2026-11-20T18:00:00Z");
        TransportLanesApi.ReportResult result = api.report(List.of(
                new LaneMeasurement(4, 9, true, 220.0, 156.2, List.of(10, 11), at, null),
                new LaneMeasurement(5, 9, false, null, 300.0, List.of(), at, "far from a road"))).join();

        assertEquals(new TransportLanesApi.ReportResult(1, 1), result);
        assertEquals("POST", seen.get(0).method());
        assertEquals("http://api.test/api/TransportLanes/report", seen.get(0).url().toString());
        JsonNode lanes = mapper.readTree(bodies.get(0)).get("lanes");
        assertEquals(2, lanes.size());
        JsonNode reachable = lanes.get(0);
        assertEquals(4, reachable.get("fromStructureId").asInt());
        assertTrue(reachable.get("reachable").asBoolean());
        assertEquals(220.0, reachable.get("roadDistance").asDouble());
        assertEquals(156.2, reachable.get("straightDistance").asDouble());
        assertEquals("[10,11]", reachable.get("routeEdgeIds").toString());
        assertEquals("2026-11-20T18:00:00Z", reachable.get("measuredAt").asText());
        JsonNode unreachable = lanes.get(1);
        assertFalse(unreachable.get("reachable").asBoolean());
        assertFalse(unreachable.has("roadDistance"), "an unreachable lane leaves the road distance out");
        assertFalse(unreachable.has("routeEdgeIds"));
        assertFalse(bodies.get(0).contains("why"), "the reason is not part of the contract");
    }

    @Test
    void report_refusesMoreThan500Lanes() {
        List<LaneMeasurement> many = Collections.nCopies(501,
                new LaneMeasurement(4, 9, false, null, null, List.of(), null, null));
        CompletionException e = assertThrows(CompletionException.class, () -> api.report(many).join());
        assertTrue(e.getCause() instanceof IllegalArgumentException);
        assertTrue(seen.isEmpty());
    }

    @Test
    void setRoadConnection_putsTheState_andA404CarriesItsStatus() throws Exception {
        body = "";
        status = 204;
        api.setRoadConnection(5, RoadConnection.Unconnected).join();
        assertEquals("PUT", seen.get(0).method());
        assertEquals("http://api.test/api/ProductionStructures/5/road-connection", seen.get(0).url().toString());
        assertEquals("Unconnected", mapper.readTree(bodies.get(0)).get("roadConnection").asText());
        status = 200;
        body = "{\"id\":5,\"roadConnection\":\"Connected\"}"; // or the structure back: the body is not read
        api.setRoadConnection(5, RoadConnection.Connected).join();
        seen.clear();

        status = 404;
        CompletionException e = assertThrows(CompletionException.class,
                () -> api.setRoadConnection(9, RoadConnection.Connected).join());
        assertTrue(e.getCause() instanceof ApiException);
        assertEquals(404, ((ApiException) e.getCause()).getStatusCode());
        assertEquals("http://api.test/api/ProductionStructures/9/road-connection", seen.get(0).url().toString());
    }
}

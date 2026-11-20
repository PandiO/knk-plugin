package net.knightsandkings.knk.api.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.ApiKeyAuthProvider;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.production.menu.StructureMenu;
import net.knightsandkings.knk.core.production.menu.StructureRow;
import net.knightsandkings.knk.core.production.menu.TransportDryRun;
import net.knightsandkings.knk.core.production.menu.TransportOrderRow;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

/** PandiO/knk-workspace#74: the structure menu routes of knk-web-api P6a (contract of #683) and the staff buttons' routes. */
class StructureMenusApiImplTest {

    private final List<Request> seen = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private volatile int status = 200;
    private volatile String body = "{}";
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
    private final StructureMenusApiImpl api = new StructureMenusApiImpl("http://api.test/api", client, mapper,
            new ApiKeyAuthProvider("secret"), executor, false);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void search_sendsThePageSearchAndFilters_asTheViewer() throws Exception {
        body = """
                {"items":[{"id":5,"structureType":"ProductionStructure","name":"Quarry","productionKindId":3,
                  "kindName":"Quarry","townId":2,"townName":"Phoenix","isRural":true,"level":1,"products":["Cobblestone"],
                  "status":"NoRoad","statusText":"Not connected to a road"}],"totalCount":1,"pageNumber":2,"pageSize":36}
                """;

        Page<StructureRow> page = api.search(new PagedQuery(2, 36, "qua", null, false, Map.of("townId", "2")), 41).join();

        assertEquals("http://api.test/api/StructureMenus/search", seen.get(0).url().toString());
        assertEquals("41", seen.get(0).header("X-Acting-User-Id"));
        JsonNode sent = mapper.readTree(bodies.get(0));
        assertEquals(2, sent.get("pageNumber").asInt());
        assertEquals("qua", sent.get("searchTerm").asText());
        assertEquals("2", sent.get("filters").get("townId").asText());
        StructureRow row = page.items().get(0);
        assertEquals("Quarry", row.name());
        assertTrue(row.rural());
        assertEquals(List.of("Cobblestone"), row.products());
        assertEquals("NoRoad", row.status());
        assertFalse(row.warehouse());
    }

    @Test
    void search_withoutAPlayerSendsNoActingUser() {
        body = "{\"items\":[],\"totalCount\":0}";

        api.search(new PagedQuery(1, 10, null, null, false, Map.of()), null).join();

        assertNull(seen.get(0).header("X-Acting-User-Id"));
    }

    @Test
    void get_aVisitorGetsNoDetail_aMemberGetsRatesStoragesWorkersAndWarnings() {
        body = "{\"id\":5,\"name\":\"Quarry\",\"status\":\"Working\",\"viewerSeesDetails\":false,\"detail\":null}";
        StructureMenu visitor = api.get(5, null).join().orElseThrow();
        assertFalse(visitor.showsDetail());

        body = """
                {"id":5,"name":"Quarry","status":"Working","viewerSeesDetails":true,"viewerIsStaff":false,
                 "detail":{"workersPresent":2,"workersTotal":2,"workersAway":0,
                   "rates":[{"productionLineId":1,"itemBlueprintId":3,"itemBlueprintName":"Cobblestone","ratePerHour":12.5,
                             "status":"Running"}],
                   "storages":[{"storageId":8,"name":"Output","purpose":"Output","totalUnits":40,"maxUnits":1000,
                                "lines":[{"itemBlueprintId":3,"itemBlueprintName":"Cobblestone","amount":40}]}],
                   "warnings":["Output is 90% full"],"openOrderCount":1}}
                """;
        StructureMenu member = api.get(5, 41).join().orElseThrow();

        assertEquals("http://api.test/api/StructureMenus/5", seen.get(1).url().toString());
        assertTrue(member.showsDetail());
        assertFalse(member.viewerIsStaff());
        assertEquals(12.5, member.detail().rates().get(0).ratePerHour(), 1e-9);
        assertEquals(40, member.detail().storages().get(0).lines().get(0).amount());
        assertEquals(1, member.detail().openOrderCount());
        assertEquals(List.of("Output is 90% full"), member.detail().warnings());
    }

    @Test
    void get_404IsEmpty() {
        status = 404;
        body = "";

        assertTrue(api.get(99, null).join().isEmpty());
    }

    @Test
    void orders_readsThePage_andAVisitorIs403() {
        body = """
                {"items":[{"id":31,"origin":"Manual","status":"InTransit","sourceStructureName":"Quarry",
                  "destinations":"North delivery","unitsRequested":10,"createdAt":"2026-11-20T10:00:00",
                  "etaAt":"2026-11-20T10:05:00Z"}],"totalCount":1,"pageNumber":1,"pageSize":28}
                """;
        Page<TransportOrderRow> page = api.orders(5, 1, 28, 41).join();

        assertEquals("http://api.test/api/StructureMenus/5/orders?page=1&pageSize=28", seen.get(0).url().toString());
        TransportOrderRow order = page.items().get(0);
        assertEquals(31, order.id());
        assertEquals(Instant.parse("2026-11-20T10:00:00Z"), order.createdAt());
        assertEquals(Instant.parse("2026-11-20T10:05:00Z"), order.etaAt());
        assertTrue(order.canCancel());
        assertFalse(order.canRetry());

        status = 403;
        body = "{\"error\":\"Forbidden\"}";
        CompletionException e = assertThrows(CompletionException.class, () -> api.orders(5, 1, 28, null).join());
        assertEquals(403, ((ApiException) e.getCause()).getStatusCode());
    }

    @Test
    void cancelAndRetry_postForTheStaffMember() throws Exception {
        body = "{\"id\":31}";

        api.cancelOrder(31, 7).join();
        api.retryOrder(32, 7).join();

        assertEquals("http://api.test/api/TransportOrders/31/cancel", seen.get(0).url().toString());
        assertEquals("POST", seen.get(0).method());
        assertEquals("7", seen.get(0).header("X-Acting-User-Id"));
        assertEquals("Cancelled in game", mapper.readTree(bodies.get(0)).get("reason").asText());
        assertEquals("http://api.test/api/TransportOrders/32/retry", seen.get(1).url().toString());
    }

    @Test
    void dryRun_readsTheTransportView() {
        body = """
                {"productionStructureId":5,"featureEnabled":true,"automaticTransport":true,"roadConnection":"Connected",
                 "outputUnits":900,"outputMaxUnits":1000,"threshold":0.8,"carrierCapacity":128,"workersPresent":2,
                 "workersTotal":2,"warnings":[],"triggerReasons":["Output 90% full"],"heldReasons":[],
                 "wouldCreate":{"valid":true,"lines":[{"itemBlueprintName":"Cobblestone","requested":128}]},
                 "openOrders":[{"id":1}]}
                """;

        TransportDryRun run = api.transportDryRun(5, 7).join().orElseThrow();

        assertEquals("http://api.test/api/ProductionStructures/5/transport", seen.get(0).url().toString());
        assertEquals("128 Cobblestone", run.wouldCreate());
        assertEquals(List.of("Output 90% full"), run.triggerReasons());
        assertEquals(1, run.openOrders());
    }
}

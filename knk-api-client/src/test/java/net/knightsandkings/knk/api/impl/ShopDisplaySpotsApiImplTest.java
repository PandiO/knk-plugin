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
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.ShopDisplaySpotsApi;
import net.knightsandkings.knk.core.production.shop.display.DisplaySpot;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

/** Display stalls P12 (PandiO/knk-workspace#250): {@code api/ShopDisplaySpots} and the best sellers from the trade stats. */
class ShopDisplaySpotsApiImplTest {

    private static final String SPOT = """
            {"id":3,"shopId":5,"shopName":"Bakery","worldName":"world","x":10,"y":64,"z":-2,"facing":"West",
             "pinnedCatalogueEntryId":12,"sortOrder":2,"label":"Front shelf","enabled":true}""";

    private final List<Request> seen = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private final List<Integer> statuses = new ArrayList<>();
    private final List<String> replies = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
        seen.add(chain.request());
        if (chain.request().body() != null) {
            Buffer buffer = new Buffer();
            chain.request().body().writeTo(buffer);
            bodies.add(buffer.readUtf8());
        } else {
            bodies.add(null);
        }
        int i = Math.min(seen.size() - 1, statuses.size() - 1);
        return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(statuses.get(i)).message("x")
                .body(ResponseBody.create(replies.get(i), MediaType.get("application/json"))).build();
    }).build();
    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final ShopDisplaySpotsApiImpl api = new ShopDisplaySpotsApiImpl("http://api.test/api", client, mapper,
            new ApiKeyAuthProvider("secret"), executor, false);

    private void reply(int status, String body) {
        statuses.add(status);
        replies.add(body);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void listsEveryPage_withThePluginKey_andSkipsRowsMissingFields() throws Exception {
        StringBuilder page1 = new StringBuilder("{\"items\":[" + SPOT);
        for (int i = 1; i < 200; i++) {
            page1.append(",{\"id\":").append(100 + i).append(",\"shopId\":5,\"worldName\":\"world\",\"x\":").append(i)
                    .append(",\"y\":64,\"z\":0}");
        }
        page1.append("],\"totalCount\":201}");
        reply(200, page1.toString());
        reply(200, "{\"items\":[{\"id\":999,\"shopId\":5}],\"totalCount\":201}");

        List<DisplaySpot> spots = api.list(5).get();

        assertEquals(200, spots.size());
        DisplaySpot first = spots.get(0);
        assertEquals(new DisplaySpot(3, 5, "world", 10, 64, -2, DisplaySpot.Facing.WEST, 12, 2, "Front shelf", true), first);
        assertEquals(DisplaySpot.Facing.SOUTH, spots.get(1).facing());
        assertTrue(spots.get(1).enabled());
        assertEquals(2, seen.size());
        assertEquals("http://api.test/api/ShopDisplaySpots/search", seen.get(0).url().toString());
        assertEquals("secret", seen.get(0).header("X-API-Key"));
        JsonNode query = mapper.readTree(bodies.get(1));
        assertEquals(2, query.get("pageNumber").asInt());
        assertEquals("5", query.get("filters").get("shopId").asText());
    }

    @Test
    void createsPinsAndDeletesASpot() throws Exception {
        reply(201, SPOT);
        DisplaySpot created = api.create(new ShopDisplaySpotsApi.SpotDraft(5, "world", 10, 64, -2, DisplaySpot.Facing.WEST, " Front shelf ")).get();
        assertEquals(3, created.id());
        JsonNode body = mapper.readTree(bodies.get(0));
        assertEquals("POST", seen.get(0).method());
        assertEquals("West", body.get("facing").asText());
        assertEquals("Front shelf", body.get("label").asText());
        assertEquals(-2, body.get("z").asInt());

        reply(200, SPOT);
        api.pin(3, null).get();
        assertEquals("PUT", seen.get(1).method());
        assertEquals("http://api.test/api/ShopDisplaySpots/3", seen.get(1).url().toString());
        assertEquals(0, mapper.readTree(bodies.get(1)).get("pinnedCatalogueEntryId").asInt());

        reply(204, "");
        api.delete(3).get();
        assertEquals("DELETE", seen.get(2).method());
    }

    @Test
    void aTakenBlock_failsWithTheApisStatus() {
        reply(409, "{\"error\":\"The block world 10 64 -2 is already a display spot.\"}");
        CompletionException error = assertThrows(CompletionException.class,
                () -> api.create(new ShopDisplaySpotsApi.SpotDraft(5, "world", 10, 64, -2, null, null)).join());
        ApiException cause = (ApiException) error.getCause();
        assertEquals(409, cause.getStatusCode());
        assertTrue(cause.getResponseBody().contains("already a display spot"));
        assertFalse(bodies.get(0).contains("\"label\""));
    }

    @Test
    void unitsSold_sumsTheHourlyRowsPerBlueprint_fromTheWindowStart() throws Exception {
        reply(200, """
                {"items":[{"itemBlueprintId":11,"unitsSold":5},{"itemBlueprintId":11,"unitsSold":7},
                 {"itemBlueprintId":14,"unitsSold":0},{"itemBlueprintId":15,"unitsSold":3}],"totalCount":4}""");

        Map<Integer, Long> sold = api.unitsSold(5, Instant.parse("2026-11-14T10:42:13Z")).get();

        assertEquals(Map.of(11, 12L, 15, 3L), sold);
        assertNull(sold.get(14));
        assertEquals("http://api.test/api/ShopTradeStats/search", seen.get(0).url().toString());
        JsonNode filters = mapper.readTree(bodies.get(0)).get("filters");
        assertEquals("5", filters.get("shopId").asText());
        assertEquals("2026-11-14T10:00:00Z", filters.get("from").asText());
    }
}

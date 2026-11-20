package net.knightsandkings.knk.api.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
import net.knightsandkings.knk.core.production.terminal.BindRequest;
import net.knightsandkings.knk.core.production.terminal.DisplaySample;
import net.knightsandkings.knk.core.production.terminal.StockMoveResult;
import net.knightsandkings.knk.core.production.terminal.StorageContainerInfo;
import net.knightsandkings.knk.core.production.terminal.TerminalPage;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

/** PandiO/knk-workspace#74: the terminal and display routes of knk-web-api P6a (contract of #683, comment on #74). */
class StorageContainersApiImplTest {

    private static final String CONTAINER = """
            {"id":7,"storageId":12,"storage":{"id":12,"name":"North delivery"},"mode":"Terminal","containerType":"Chest",
             "isDouble":true,"slots":54,"worldName":"world","x":1,"y":64,"z":0,"label":null,"storageMaxUnits":10800}
            """;

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
        }
        int i = Math.min(seen.size() - 1, statuses.size() - 1);
        return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(statuses.get(i)).message("x")
                .body(ResponseBody.create(replies.get(i), MediaType.get("application/json"))).build();
    }).build();
    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final StorageContainersApiImpl api = new StorageContainersApiImpl("http://api.test/api", client, mapper,
            new ApiKeyAuthProvider("secret"), executor, false);

    private void reply(int status, String body) {
        statuses.add(status);
        replies.add(body);
    }

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void byBlock_readsTheBinding_andA404IsUnbound() {
        reply(200, CONTAINER);
        reply(404, "");

        StorageContainerInfo info = api.byBlock("world", 1, 64, 0).join().orElseThrow();
        Optional<StorageContainerInfo> none = api.byBlock("my world", 5, 64, 0).join();

        assertEquals("http://api.test/api/StorageContainers/by-block?world=world&x=1&y=64&z=0", seen.get(0).url().toString());
        assertEquals("secret", seen.get(0).header("X-API-Key"));
        assertEquals(7, info.id());
        assertEquals(12, info.storageId());
        assertEquals("North delivery", info.displayName());
        assertEquals(StorageContainerInfo.Mode.TERMINAL, info.mode());
        assertTrue(info.isDouble());
        assertEquals(54, info.slots());
        assertTrue(none.isEmpty());
        assertEquals("my+world", seen.get(1).url().queryParameter("world").replace(' ', '+'));
    }

    @Test
    void byBlock_otherFailuresCarryTheStatus() {
        reply(500, "{\"error\":\"boom\"}");

        CompletionException e = assertThrows(CompletionException.class, () -> api.byBlock("world", 1, 2, 3).join());
        assertEquals(500, ((ApiException) e.getCause()).getStatusCode());
    }

    @Test
    void list_pagesThroughTheSearch_withTheModeAndWorldFilters() throws Exception {
        StringBuilder page1 = new StringBuilder("{\"items\":[");
        for (int i = 1; i <= 200; i++) {
            page1.append(i > 1 ? "," : "").append("{\"id\":").append(i).append(",\"storageId\":3,\"mode\":\"Display\",")
                    .append("\"containerType\":\"Barrel\",\"isDouble\":false,\"slots\":27,\"worldName\":\"world\",\"x\":")
                    .append(i).append(",\"y\":64,\"z\":0}");
        }
        page1.append("],\"totalCount\":201,\"pageNumber\":1,\"pageSize\":200}");
        reply(200, page1.toString());
        reply(200, "{\"items\":[{\"id\":201,\"storageId\":3,\"mode\":\"Display\",\"worldName\":\"world\",\"x\":0,\"y\":1,\"z\":2},"
                + "{\"id\":202,\"mode\":\"Backed\",\"storageId\":3,\"worldName\":\"world\",\"x\":0,\"y\":1,\"z\":3}],"
                + "\"totalCount\":201,\"pageNumber\":2,\"pageSize\":200}");

        List<StorageContainerInfo> all = api.list(StorageContainerInfo.Mode.DISPLAY, "world").join();

        assertEquals(201, all.size()); // the Backed row (later mode) is skipped
        assertEquals(2, seen.size());
        JsonNode body = mapper.readTree(bodies.get(1));
        assertEquals(2, body.get("pageNumber").asInt());
        assertEquals("Display", body.get("filters").get("mode").asText());
        assertEquals("world", body.get("filters").get("worldName").asText());
        assertEquals(27, all.get(200).slots());
    }

    @Test
    void bind_postsTheCanonicalBlock_andUnbindDeletes() throws Exception {
        reply(201, CONTAINER);
        reply(204, "");

        StorageContainerInfo info = api.bind(new BindRequest(12, StorageContainerInfo.Mode.TERMINAL, "Chest", true, "world",
                1, 64, 0, null)).join();
        api.unbind(7).join();

        JsonNode body = mapper.readTree(bodies.get(0));
        assertEquals(12, body.get("storageId").asInt());
        assertEquals("Terminal", body.get("mode").asText());
        assertEquals("Chest", body.get("containerType").asText());
        assertTrue(body.get("isDouble").asBoolean());
        assertFalse(body.has("label"));
        assertEquals(7, info.id());
        assertEquals("DELETE", seen.get(1).method());
        assertEquals("http://api.test/api/StorageContainers/7", seen.get(1).url().toString());
    }

    @Test
    void terminal_actsForThePlayer_andReadsStockLinesThenUniqueItems() {
        reply(200, """
                {"containerId":7,"storageId":12,"storageName":"North delivery","totalUnits":46,"capacityUsed":0.004,
                 "maxUnits":10800,"isLocked":false,"page":1,"pageSize":45,"totalCount":2,
                 "entries":[{"itemBlueprintId":3,"itemBlueprintName":"Cobblestone","amount":45},
                            {"itemBlueprintId":9,"itemBlueprintName":"Sword","amount":1,"itemInstanceId":77,
                             "customDisplayName":"Old Faithful"}]}
                """);

        TerminalPage page = api.terminal(7, 1, 45, 41).join();

        assertEquals("http://api.test/api/StorageContainers/7/terminal?page=1&pageSize=45", seen.get(0).url().toString());
        assertEquals("41", seen.get(0).header("X-Acting-User-Id"));
        assertEquals(2, page.entries().size());
        assertFalse(page.entries().get(0).unique());
        assertTrue(page.entries().get(1).unique());
        assertEquals("Old Faithful", page.entries().get(1).name());
        assertEquals(10800L, page.maxUnits());
        assertFalse(page.hasNext());
    }

    @Test
    void putAndTake_sendTheKeyAndActingUser_andReadTheStockResult() throws Exception {
        reply(200, "{\"status\":\"Applied\",\"replayed\":false,\"accepted\":10,\"lines\":[{\"accepted\":10,\"rejectReason\":\"None\"}]}");
        reply(200, "{\"status\":\"PartiallyApplied\",\"replayed\":false,\"accepted\":3,"
                + "\"lines\":[{\"accepted\":3,\"requested\":5,\"rejectReason\":\"NotEnoughStock\"}]}");

        StockMoveResult put = api.put(7, 41, 3, null, 10, "click-1").join();
        StockMoveResult take = api.take(7, 42, 3, null, 5, "click-2").join();

        assertEquals("http://api.test/api/StorageContainers/7/put", seen.get(0).url().toString());
        assertEquals("41", seen.get(0).header("X-Acting-User-Id"));
        JsonNode body = mapper.readTree(bodies.get(0));
        assertEquals(3, body.get("itemBlueprintId").asInt());
        assertEquals(10, body.get("amount").asLong());
        assertEquals("click-1", body.get("idempotencyKey").asText());
        assertFalse(body.has("itemInstanceId"));
        assertEquals(10, put.accepted());
        assertTrue(put.rejectReasons().isEmpty());
        assertEquals("http://api.test/api/StorageContainers/7/take", seen.get(1).url().toString());
        assertEquals(3, take.accepted());
        assertEquals(List.of("NotEnoughStock"), take.rejectReasons());
    }

    @Test
    void put_aForbiddenPlayerIs403() {
        reply(403, "{\"error\":\"Forbidden\"}");

        CompletionException e = assertThrows(CompletionException.class, () -> api.put(7, 41, 3, null, 1, "k").join());
        assertEquals(403, ((ApiException) e.getCause()).getStatusCode());
    }

    @Test
    void display_readsTheSample() {
        reply(200, "{\"containerId\":8,\"storageId\":12,\"slots\":27,\"fillRatio\":0.5,\"filledSlots\":14,"
                + "\"items\":[{\"itemBlueprintId\":3,\"itemBlueprintName\":\"Cobblestone\",\"stacks\":8},"
                + "{\"itemBlueprintId\":4,\"stacks\":6},{\"itemBlueprintId\":5,\"stacks\":0}]}");

        DisplaySample sample = api.display(8).join();

        assertEquals(0.5, sample.fillRatio(), 1e-9);
        assertEquals(14, sample.filledSlots());
        assertEquals(2, sample.items().size());
        assertNull(sample.items().get(1).itemBlueprintName());
    }
}

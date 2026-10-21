package net.knightsandkings.knk.api.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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

import net.knightsandkings.knk.api.auth.ApiKeyAuthProvider;
import net.knightsandkings.knk.api.dto.MinecraftCatalogSnapshotDto;
import net.knightsandkings.knk.core.catalog.CatalogEntry;
import net.knightsandkings.knk.core.catalog.CatalogSnapshot;
import net.knightsandkings.knk.core.exception.ApiException;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

/**
 * KNG-200: the snapshot PUT matches IMPLEMENTATION_PLAN.md §5.2a (method, URL, X-API-Key, camelCase body);
 * a refused snapshot carries the ApiException with its status and body.
 */
class MinecraftCatalogApiImplTest {

    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final List<Request> seen = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private int status = 200;
    private String responseJson = "{\"id\":12,\"status\":\"Completed\"}";

    private final OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
        Request request = chain.request();
        seen.add(request);
        Buffer buffer = new Buffer();
        request.body().writeTo(buffer);
        bodies.add(buffer.readUtf8());
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("x")
                .body(ResponseBody.create(responseJson, MediaType.get("application/json"))).build();
    }).build();

    private final MinecraftCatalogApiImpl api = new MinecraftCatalogApiImpl("http://api.test/api", client, mapper,
        new ApiKeyAuthProvider("secret-key"), executor, false);

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private static MinecraftCatalogSnapshotDto snapshot() {
        return MinecraftCatalogSnapshotDto.from(CatalogSnapshot.of("1.21.10", "git-Paper-123 (MC: 1.21.10)", List.of(
            new CatalogEntry("minecraft:stone", true, true, 64, "block.minecraft.stone", "Stone", "minecraft:stone"),
            new CatalogEntry("minecraft:apple", true, false, 64, "item.minecraft.apple", null, null))));
    }

    @Test
    void putSnapshot_sendsTheContractShape() throws Exception {
        api.putSnapshot(12, snapshot()).join();

        Request request = seen.get(0);
        assertEquals("PUT", request.method());
        assertEquals("http://api.test/api/MinecraftCatalog/scans/12/snapshot", request.url().toString());
        assertEquals("secret-key", request.header("X-API-Key"));
        assertTrue(request.body().contentType().toString().startsWith("application/json"));

        JsonNode body = mapper.readTree(bodies.get(0));
        assertEquals(1, body.get("schemaVersion").asInt());
        assertEquals("1.21.10", body.get("minecraftVersion").asText());
        assertEquals("git-Paper-123 (MC: 1.21.10)", body.get("serverVersion").asText());
        assertEquals("86979e0606b8c36d782bf7bc0100dc2d3559aa53d714900419f0850431d5fb12", body.get("contentHash").asText());
        assertEquals(2, body.get("entries").size());

        JsonNode apple = body.get("entries").get(0);
        assertEquals("minecraft:apple", apple.get("key").asText());
        assertTrue(apple.get("isItem").asBoolean());
        assertFalse(apple.get("isBlock").asBoolean());
        assertEquals(64, apple.get("maxStackSize").asInt());
        assertEquals("item.minecraft.apple", apple.get("translationKey").asText());
        assertFalse(apple.has("displayName"), "nulls are omitted");
        assertFalse(apple.has("defaultBlockState"), "nulls are omitted");

        JsonNode stone = body.get("entries").get(1);
        assertEquals("minecraft:stone", stone.get("key").asText());
        assertEquals("Stone", stone.get("displayName").asText());
        assertEquals("minecraft:stone", stone.get("defaultBlockState").asText());
        assertEquals(List.of("defaultBlockState", "displayName", "isBlock", "isItem", "key", "maxStackSize", "translationKey"),
            fieldNames(stone));
    }

    @Test
    void aRefusedSnapshot_failsWithTheStatusAndBody() {
        status = 400;
        responseJson = "{\"error\":\"HashMismatch\"}";

        CompletionException failure = assertThrows(CompletionException.class, () -> api.putSnapshot(12, snapshot()).join());

        Throwable cause = failure.getCause();
        while (cause != null && !(cause instanceof ApiException)) {
            cause = cause.getCause();
        }
        assertInstanceOf(ApiException.class, cause);
        assertEquals(400, ((ApiException) cause).getStatusCode());
        assertTrue(((ApiException) cause).getResponseBody().contains("HashMismatch"));
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        names.sort(String::compareTo);
        return names;
    }
}

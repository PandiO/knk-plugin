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
import net.knightsandkings.knk.core.catalog.CatalogEnchantment;
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
 * KNG-200/KNG-250: the snapshot PUT matches IMPLEMENTATION_PLAN.md §5.2a and §12.2 (method, URL, X-API-Key,
 * camelCase schema-2 body with enchantments); a refused snapshot carries the ApiException with its status and body.
 */
class MinecraftCatalogApiImplTest {

    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final List<Request> seen = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private final List<Integer> readTimeouts = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private int status = 200;
    private String responseJson = "{\"id\":12,\"status\":\"Completed\"}";

    private final OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
        Request request = chain.request();
        seen.add(request);
        readTimeouts.add(chain.readTimeoutMillis());
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
        return MinecraftCatalogSnapshotDto.from(CatalogSnapshot.of("26.3", "git-Paper-205 (MC: 26.3)", List.of(
            new CatalogEntry("minecraft:stone", true, true, 64, "block.minecraft.stone", "Stone", "minecraft:stone"),
            new CatalogEntry("minecraft:apple", true, false, 64, "item.minecraft.apple", null, null)), List.of(
            new CatalogEnchantment("minecraft:sharpness", false, 5, "enchantment.minecraft.sharpness", "Sharpness", null),
            new CatalogEnchantment("knk:flash_chaos", true, 1, null, "Flash Chaos", "SUPPORT"))));
    }

    @Test
    void putSnapshot_waitsLongerThanTheSharedReadTimeout_ForTheApiToApplyIt() {
        api.putSnapshot(12, snapshot()).join();

        assertEquals((int) MinecraftCatalogApiImpl.SNAPSHOT_READ_TIMEOUT.toMillis(), readTimeouts.get(0));
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
        assertEquals(2, body.get("schemaVersion").asInt());
        assertEquals("26.3", body.get("minecraftVersion").asText());
        assertEquals("git-Paper-205 (MC: 26.3)", body.get("serverVersion").asText());
        assertEquals("ab2c65626e6508eb4c169dabefda5ef7570f94c8d966f31adecc624dfe823154", body.get("contentHash").asText());
        assertEquals(List.of("contentHash", "enchantments", "entries", "minecraftVersion", "schemaVersion", "serverVersion"),
            fieldNames(body));
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

        assertEquals(2, body.get("enchantments").size());
        JsonNode flashChaos = body.get("enchantments").get(0);
        assertEquals("knk:flash_chaos", flashChaos.get("key").asText());
        assertTrue(flashChaos.get("isCustom").asBoolean());
        assertEquals(1, flashChaos.get("maxLevel").asInt());
        assertEquals("Flash Chaos", flashChaos.get("displayName").asText());
        assertEquals("SUPPORT", flashChaos.get("category").asText());
        assertFalse(flashChaos.has("translationKey"), "nulls are omitted");

        JsonNode sharpness = body.get("enchantments").get(1);
        assertEquals("minecraft:sharpness", sharpness.get("key").asText());
        assertFalse(sharpness.get("isCustom").asBoolean());
        assertEquals(5, sharpness.get("maxLevel").asInt());
        assertEquals("enchantment.minecraft.sharpness", sharpness.get("translationKey").asText());
        assertEquals("Sharpness", sharpness.get("displayName").asText());
        assertFalse(sharpness.has("category"), "nulls are omitted");
        assertEquals(List.of("displayName", "isCustom", "key", "maxLevel", "translationKey"), fieldNames(sharpness));
    }

    @Test
    void putSnapshot_sendsAnEmptyEnchantmentList_asAnEmptyArray() throws Exception {
        MinecraftCatalogSnapshotDto snapshot = MinecraftCatalogSnapshotDto.from(CatalogSnapshot.of("26.3", "paper", List.of(
            new CatalogEntry("minecraft:stone", true, true, 64, "block.minecraft.stone", "Stone", "minecraft:stone"),
            new CatalogEntry("minecraft:apple", true, false, 64, "item.minecraft.apple", null, null)), List.of()));

        api.putSnapshot(12, snapshot).join();

        JsonNode body = mapper.readTree(bodies.get(0));
        assertTrue(body.get("enchantments").isArray());
        assertEquals(0, body.get("enchantments").size());
        assertEquals("9846ad62d84d42d5a05e0cbac900ab6f671bf3bb0dd7c1bb5961aa2316f63c3e", body.get("contentHash").asText());
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

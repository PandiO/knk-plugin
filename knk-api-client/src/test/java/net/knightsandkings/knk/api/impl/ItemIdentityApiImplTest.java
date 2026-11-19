package net.knightsandkings.knk.api.impl;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.auth.ApiKeyAuthProvider;
import net.knightsandkings.knk.core.items.identity.ItemConversionLog;
import net.knightsandkings.knk.core.items.identity.MaterialBlueprintMap;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PandiO/knk-workspace#141: the routes, the plugin key, the map's parsing and the counts body. */
class ItemIdentityApiImplTest {

    private final List<Request> seen = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private volatile int status = 200;
    private volatile String body = "{\"generatedAt\":\"2026-11-19T12:00:00Z\",\"entries\":["
            + "{\"materialKey\":\"minecraft:oak_log\",\"blueprintId\":10,\"shadow\":true},"
            + "{\"materialKey\":\"minecraft:bread\",\"blueprintId\":20,\"shadow\":false},"
            + "{\"materialKey\":null,\"blueprintId\":5},{\"materialKey\":\"minecraft:stone\",\"blueprintId\":0}]}";
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
    private final ItemIdentityApiImpl api = new ItemIdentityApiImpl("http://api.test/api", client,
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false),
            new ApiKeyAuthProvider("secret"), executor, false);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void fetchMaterialMap_readsTheMapWithTheKey_andDropsBrokenRows() {
        MaterialBlueprintMap map = api.fetchMaterialMap().join();

        assertEquals("GET", seen.get(0).method());
        assertEquals("http://api.test/api/ItemIdentity/material-map", seen.get(0).url().toString());
        assertEquals("secret", seen.get(0).header("X-API-Key"));
        assertEquals(2, map.size());
        assertEquals(10, map.blueprintIdOf("minecraft:oak_log"));
        assertTrue(map.find("minecraft:oak_log").orElseThrow().shadow());
        assertEquals(20, map.blueprintIdOf("minecraft:bread"));
    }

    @Test
    void reportConversionCounts_postsTheLines_andNothingWhenEmpty() {
        body = "{\"applied\":1}";
        api.reportConversionCounts(List.of()).join();
        assertTrue(seen.isEmpty());

        api.reportConversionCounts(List.of(new ItemConversionLog.Count("pickup", "vanilla_match", "minecraft:oak_log", 12))).join();

        assertEquals("POST", seen.get(0).method());
        assertEquals("http://api.test/api/ItemIdentity/conversion-counts", seen.get(0).url().toString());
        assertEquals("{\"counts\":[{\"source\":\"pickup\",\"outcome\":\"vanilla_match\",\"materialKey\":\"minecraft:oak_log\",\"count\":12}]}",
                bodies.get(0));
    }

    @Test
    void reportConversionCounts_failsOnAnApiError() {
        status = 500;
        body = "{}";
        assertThrows(CompletionException.class, () -> api.reportConversionCounts(
                List.of(new ItemConversionLog.Count("pickup", "vanilla_match", "minecraft:oak_log", 1))).join());
    }
}

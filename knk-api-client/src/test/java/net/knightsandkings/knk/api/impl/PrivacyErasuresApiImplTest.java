package net.knightsandkings.knk.api.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.NoAuthProvider;
import net.knightsandkings.knk.core.privacy.ErasedAccount;
import net.knightsandkings.knk.core.privacy.ErasureFeedPage;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** PandiO/knk-workspace#184: the request and JSON match knk-web-api's PrivacyErasuresController / PrivacyErasureFeedDto. */
class PrivacyErasuresApiImplTest {

    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final List<Request> seen = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private int status = 200;
    private String responseJson = "{}";

    private final OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
        Request request = chain.request();
        seen.add(request);
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("x")
            .body(ResponseBody.create(responseJson, MediaType.get("application/json"))).build();
    }).build();

    private final PrivacyErasuresApiImpl api = new PrivacyErasuresApiImpl("http://api.test/api", client, mapper,
        new NoAuthProvider(), executor, false);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void since_readsThePage() {
        responseJson = """
            {"items":[{"erasureId":5,"userId":42,"formerUuid":"00000000-0000-0000-0000-0000000000a1","erasedAt":"2026-11-05T20:00:00"},
                      {"erasureId":6,"userId":43,"formerUuid":null,"erasedAt":"2026-11-05T20:00:00"}],
             "lastId":6,"hasMore":true,"latestId":9}""";

        ErasureFeedPage page = api.since(4, 2).join();

        assertEquals("http://api.test/api/privacy/erasures?since=4&limit=2", seen.get(0).url().toString());
        assertEquals("GET", seen.get(0).method());
        assertEquals(List.of(new ErasedAccount(5, 42, UUID.fromString("00000000-0000-0000-0000-0000000000a1")),
            new ErasedAccount(6, 43, null)), page.items());
        assertEquals(6, page.lastId());
        assertTrue(page.hasMore());
        assertEquals(9, page.latestId());
    }

    /**
     * PandiO/knk-workspace#486: the body knk-web-api actually sends. {@code Program.cs} sets {@code PropertyNamingPolicy
     * = null} and {@code PrivacyErasureFeedDto} has no {@code [JsonPropertyName]}, so the names are PascalCase. Read
     * with camelCase-only names this parsed as an empty page with lastId 0: nothing applied, the cursor never moved,
     * every connect asked {@code since=0}.
     */
    @Test
    void since_readsTheApisPascalCaseBody() {
        responseJson = """
            {"Items":[{"ErasureId":1,"UserId":11,"FormerUuid":"00000000-0000-0000-0000-0000000000b2","ErasedAt":"2026-11-14T21:14:03.1234567"},
                      {"ErasureId":6,"UserId":15,"FormerUuid":null,"ErasedAt":"2026-11-14T22:20:41.7654321"}],
             "LastId":6,"HasMore":false,"LatestId":6}""";

        ErasureFeedPage page = api.since(0, 100).join();

        assertEquals(List.of(new ErasedAccount(1, 11, UUID.fromString("00000000-0000-0000-0000-0000000000b2")),
            new ErasedAccount(6, 15, null)), page.items());
        assertEquals(6, page.lastId());
        assertFalse(page.hasMore());
        assertEquals(6, page.latestId());
    }

    @Test
    void since_readsAPascalCasePageWithMore() {
        responseJson = "{\"Items\":[{\"ErasureId\":101,\"UserId\":7,\"FormerUuid\":null,\"ErasedAt\":\"2026-11-14T22:00:00\"}],"
            + "\"LastId\":101,\"HasMore\":true,\"LatestId\":250}";

        ErasureFeedPage page = api.since(100, 1).join();

        assertEquals(List.of(new ErasedAccount(101, 7, null)), page.items());
        assertEquals(101, page.lastId());
        assertTrue(page.hasMore());
        assertEquals(250, page.latestId());
    }

    @Test
    void anEmptyFeed() {
        responseJson = "{\"items\":[],\"lastId\":0,\"hasMore\":false,\"latestId\":0}";

        ErasureFeedPage page = api.since(-3, 100).join();

        assertEquals("http://api.test/api/privacy/erasures?since=0&limit=100", seen.get(0).url().toString());
        assertTrue(page.items().isEmpty());
        assertFalse(page.hasMore());
    }

    @Test
    void anError_FailsTheFuture() {
        status = 401;
        responseJson = "{\"error\":\"Unauthorized\"}";

        assertThrows(CompletionException.class, () -> api.since(0, 100).join());
    }
}

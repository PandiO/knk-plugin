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

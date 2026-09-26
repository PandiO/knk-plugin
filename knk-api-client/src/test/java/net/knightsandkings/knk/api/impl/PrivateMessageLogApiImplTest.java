package net.knightsandkings.knk.api.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.ApiKeyAuthProvider;
import net.knightsandkings.knk.core.domain.messaging.PrivateMessageLogEntry;
import net.knightsandkings.knk.core.domain.messaging.PrivateMessageLogEntry.Outcome;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.PrivateMessageLogApi.BatchResult;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

/** KNG-18 Phase 3: POST api/private-message-log/batch - body shape, service key, failures. */
class PrivateMessageLogApiImplTest {

    private final List<Request> seen = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ObjectMapper mapper = new ObjectMapper();
    private int nextStatus = 200;
    private String nextBody = "{\"accepted\":2,\"duplicates\":0}";

    private final OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
        Request request = chain.request();
        seen.add(request);
        Buffer buffer = new Buffer();
        if (request.body() != null) {
            request.body().writeTo(buffer);
        }
        bodies.add(buffer.readUtf8());
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(nextStatus).message("x")
                .body(ResponseBody.create(nextBody, MediaType.get("application/json"))).build();
    }).build();

    private final PrivateMessageLogApiImpl api = new PrivateMessageLogApiImpl("http://api.test/api", client, mapper,
            new ApiKeyAuthProvider("secret"), executor, false);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void submitBatch_PostsTheEntriesWithTheServiceKey() throws Exception {
        UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID alice = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        List<PrivateMessageLogEntry> entries = List.of(
                new PrivateMessageLogEntry(id, Instant.parse("2026-09-26T19:04:05.123Z"), alice, "Alice", null, "CONSOLE",
                        "hi", Outcome.BLOCKED_RATE_LIMITED, true),
                new PrivateMessageLogEntry(UUID.randomUUID(), Instant.parse("2026-09-26T19:04:06Z"), null, "CONSOLE", alice,
                        "Alice", "yo", Outcome.DELIVERED, false));

        BatchResult result = api.submitBatch(entries).join();

        assertEquals(new BatchResult(2, 0), result);
        Request request = seen.get(0);
        assertEquals("POST", request.method());
        assertEquals("http://api.test/api/private-message-log/batch", request.url().toString());
        assertEquals("secret", request.header("X-API-Key"));

        JsonNode body = mapper.readTree(bodies.get(0));
        assertEquals(2, body.size());
        JsonNode first = body.get(0);
        assertEquals(id.toString(), first.get("clientMessageId").asText());
        assertEquals("2026-09-26T19:04:05.123Z", first.get("sentAt").asText());
        assertEquals(alice.toString(), first.get("senderUuid").asText());
        assertTrue(first.get("recipientUuid").isNull());
        assertEquals("CONSOLE", first.get("recipientName").asText());
        assertEquals("BlockedRateLimited", first.get("outcome").asText());
        assertTrue(first.get("viaReply").asBoolean());
        assertEquals("Delivered", body.get(1).get("outcome").asText());
    }

    @Test
    void everyOutcome_HasAnApiName() {
        assertEquals(List.of("Delivered", "BlockedIgnored", "BlockedRateLimited", "BlockedFrozen"),
                List.of(Outcome.values()).stream().map(PrivateMessageLogApiImpl::outcome).toList());
    }

    @Test
    void refusal_CarriesTheStatus() {
        nextStatus = 401;
        nextBody = "{\"error\":\"Unauthorized\"}";

        CompletionException failure = assertThrows(CompletionException.class, () -> api.submitBatch(List.of(
                new PrivateMessageLogEntry(UUID.randomUUID(), Instant.now(), null, "CONSOLE", null, "CONSOLE", "x",
                        Outcome.DELIVERED, false))).join());

        ApiException api = assertInstanceOf(ApiException.class, failure.getCause().getCause());
        assertEquals(401, api.getStatusCode());
    }
}

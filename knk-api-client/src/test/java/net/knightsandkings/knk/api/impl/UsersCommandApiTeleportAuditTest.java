package net.knightsandkings.knk.api.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.auth.NoAuthProvider;
import net.knightsandkings.knk.core.teleport.TeleportAudit;
import net.knightsandkings.knk.core.teleport.TeleportKind;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** POST /api/users/{id}/teleport-audit (docs/specs/teleport/DESIGN.md §3.10): route, body, actor header. */
class UsersCommandApiTeleportAuditTest {

    private final List<Request> seen = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
        Request request = chain.request();
        seen.add(request);
        Buffer buffer = new Buffer();
        if (request.body() != null) {
            request.body().writeTo(buffer);
        }
        bodies.add(buffer.readUtf8());
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(204).message("No Content")
                .body(ResponseBody.create("", MediaType.get("application/json"))).build();
    }).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final UsersCommandApiImpl api = new UsersCommandApiImpl("http://api.test/api", client, mapper,
            new NoAuthProvider(), executor, false);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void postsTheTeleportUnderTheVisitedPlayerWithTheActorHeader() throws Exception {
        TeleportAudit audit = new TeleportAudit(TeleportKind.STAFF, 1, 1, 2,
                new TeleportAudit.Point("world", 0.5, 64, -3.5), new TeleportAudit.Point("world_nether", 10, 70, 20),
                true, null, false);

        api.withActor(1).recordTeleportAudit(audit).join();

        Request request = seen.get(0);
        assertEquals("POST", request.method());
        assertEquals("http://api.test/api/Users/2/teleport-audit", request.url().toString());
        assertEquals("1", request.header(UsersCommandApiImpl.ACTING_USER_HEADER));
        JsonNode body = mapper.readTree(bodies.get(0));
        assertEquals("STAFF", body.get("kind").asText());
        assertEquals(1, body.get("subjectUserId").asInt());
        assertEquals(2, body.get("visitedUserId").asInt());
        assertEquals("world", body.get("from").get("world").asText());
        assertEquals(-3.5, body.get("from").get("z").asDouble());
        assertEquals("world_nether", body.get("to").get("world").asText());
        assertTrue(body.get("silent").asBoolean());
        assertEquals("command", body.get("via").asText());
    }

    @Test
    void consoleTeleportIsSentWithoutActor() throws Exception {
        TeleportAudit audit = new TeleportAudit(TeleportKind.STAFF, null, 3, 2,
                new TeleportAudit.Point("world", 0, 64, 0), new TeleportAudit.Point("world", 1, 64, 1),
                false, null, true);

        api.recordTeleportAudit(audit).join();

        assertEquals("http://api.test/api/Users/3/teleport-audit", seen.get(0).url().toString());
        assertEquals(null, seen.get(0).header(UsersCommandApiImpl.ACTING_USER_HEADER));
        assertEquals("console", mapper.readTree(bodies.get(0)).get("via").asText());
    }
}

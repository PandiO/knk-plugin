package net.knightsandkings.knk.api.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import net.knightsandkings.knk.api.auth.NoAuthProvider;
import net.knightsandkings.knk.api.serialization.LenientOffsetDateTimeDeserializer;
import net.knightsandkings.knk.core.domain.users.UserIgnore;
import net.knightsandkings.knk.core.ports.api.UserIgnoresApi.AddResult;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** KNG-18 Phase 2: routes, list mapping and refusal codes of api/users/{id}/ignores. */
class UserIgnoresApiImplTest {

    private final List<Request> seen = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private int nextStatus = 204;
    private String nextBody = "";

    private final OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
        seen.add(chain.request());
        return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(nextStatus).message("x")
                .body(ResponseBody.create(nextBody, MediaType.get("application/json"))).build();
    }).build();

    private final UserIgnoresApiImpl api = new UserIgnoresApiImpl("http://api.test/api", client, mapper(),
            new NoAuthProvider(), executor, false);

    private static ObjectMapper mapper() {
        JavaTimeModule time = new JavaTimeModule();
        time.addDeserializer(OffsetDateTime.class, new LenientOffsetDateTimeDeserializer());
        return new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).registerModule(time);
    }

    private void respond(int status, String body) {
        nextStatus = status;
        nextBody = body;
    }

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void list_mapsEntries() {
        UUID bob = UUID.randomUUID();
        respond(200, "[{\"ignoredUserId\":2,\"ignoredUsername\":\"Bob\",\"ignoredUuid\":\"" + bob
                + "\",\"createdAt\":\"2026-09-26T19:04:00\"},"
                + "{\"ignoredUserId\":3,\"ignoredUsername\":\"WebOnly\",\"ignoredUuid\":null,\"createdAt\":\"2026-09-26T20:00:00Z\"}]");

        List<UserIgnore> list = api.list(7).join();

        assertEquals("GET", seen.get(0).method());
        assertEquals("http://api.test/api/users/7/ignores", seen.get(0).url().toString());
        assertEquals(new UserIgnore(2, "Bob", bob, OffsetDateTime.of(2026, 9, 26, 19, 4, 0, 0, ZoneOffset.UTC)), list.get(0));
        assertNull(list.get(1).ignoredUuid());
    }

    @Test
    void add_putsAndReportsIgnored() {
        assertEquals(AddResult.IGNORED, api.add(7, 2).join());

        assertEquals("PUT", seen.get(0).method());
        assertEquals("http://api.test/api/users/7/ignores/2", seen.get(0).url().toString());
    }

    @Test
    void add_mapsEachRefusal() {
        respond(400, "{\"error\":\"CannotIgnoreStaff\",\"message\":\"You can't ignore staff.\"}");
        assertEquals(AddResult.CANNOT_IGNORE_STAFF, api.add(7, 2).join());
        respond(400, "{\"error\":\"SelfIgnore\"}");
        assertEquals(AddResult.SELF_IGNORE, api.add(7, 7).join());
        respond(409, "{\"error\":\"IgnoreLimitReached\"}");
        assertEquals(AddResult.LIMIT_REACHED, api.add(7, 2).join());
        respond(404, "{\"error\":\"UserNotFound\"}");
        assertEquals(AddResult.USER_NOT_FOUND, api.add(7, 99).join());
    }

    @Test
    void add_serverErrorFails() {
        respond(500, "boom");
        assertThrows(CompletionException.class, () -> api.add(7, 2).join());
        respond(400, "not json");
        assertThrows(CompletionException.class, () -> api.add(7, 2).join());
    }

    @Test
    void remove_deletes() {
        api.remove(7, 2).join();

        assertEquals("DELETE", seen.get(0).method());
        assertEquals("http://api.test/api/users/7/ignores/2", seen.get(0).url().toString());
    }
}

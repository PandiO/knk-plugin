package net.knightsandkings.knk.api.impl;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import net.knightsandkings.knk.api.auth.NoAuthProvider;
import net.knightsandkings.knk.core.domain.users.UserDetail;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * KNG-81: POST /api/Users answers {"user": {...}, "linkCode": ...}. create() read that body as the
 * user itself, so the created account came back with every field null and the first-join path in
 * UsersDataAccess threw on the null coins before it could cache the new user.
 */
class UsersCommandApiCreateTest {

    private static final UUID PLAYER = UUID.fromString("c051f74f-36b6-436b-bf10-37d621d1760a");

    // The body the API sent on a live first join (trimmed of fields UserDto doesn't map).
    private static final String WRAPPED = """
        {"user":{"id":37,"username":"__dominic14__","uuid":"c051f74f-36b6-436b-bf10-37d621d1760a","email":null,
                 "coins":250,"gems":50,"experiencePoints":0,"isFullAccount":false,"permissionGroups":[{"id":28,"name":"Default"}],
                 "createdAt":"2026-10-10T23:59:51.1697650","isActive":true},
         "linkCode":null}
        """;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    private UsersCommandApiImpl apiAnswering(String body) {
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain ->
            new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(201).message("Created")
                .body(ResponseBody.create(body, MediaType.get("application/json"))).build()).build();
        // Configured like KnkApiClient's mapper.
        ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .registerModule(new JavaTimeModule());
        return new UsersCommandApiImpl("http://api.test/api", client, mapper, new NoAuthProvider(), executor, false);
    }

    private static UserDetail seed() {
        return new UserDetail(null, "__dominic14__", PLAYER, null, null, new Date());
    }

    @Test
    void readsTheCreatedUserInsideTheWrapper() {
        UserDetail created = apiAnswering(WRAPPED).create(seed()).join();

        assertEquals(37, created.id());
        assertEquals("__dominic14__", created.username());
        assertEquals(PLAYER, created.uuid());
        assertEquals(250, created.coins());
    }

    @Test
    void stillReadsABareUserBody() {
        String bare = "{\"id\":37,\"username\":\"__dominic14__\",\"uuid\":\"" + PLAYER + "\",\"coins\":250}";

        UserDetail created = apiAnswering(bare).create(seed()).join();

        assertEquals(37, created.id());
        assertEquals(250, created.coins());
    }
}

package net.knightsandkings.knk.api.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.auth.NoAuthProvider;
import net.knightsandkings.knk.core.ports.api.UsersCommandApi;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Content port CP7: {@code withActor} sends {@code X-Acting-User-Id}; the plain instance doesn't. */
class UsersCommandApiActorTest {

    private final List<Request> seen = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
        seen.add(chain.request());
        return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(204).message("No Content")
                .body(ResponseBody.create("", MediaType.get("application/json"))).build();
    }).build();
    private final UsersCommandApiImpl api = new UsersCommandApiImpl("http://api.test/api", client, new ObjectMapper(),
            new NoAuthProvider(), executor, false);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void actorInstanceAddsTheHeaderToEveryCall() {
        UsersCommandApi acting = api.withActor(42);

        acting.removeGroupMembership(7, 3).join();
        acting.revokePermission(7, "knk.x").join();
        api.removeGroupMembership(7, 3).join();

        assertEquals("42", seen.get(0).header(UsersCommandApiImpl.ACTING_USER_HEADER));
        assertEquals("42", seen.get(1).header(UsersCommandApiImpl.ACTING_USER_HEADER));
        assertNull(seen.get(2).header(UsersCommandApiImpl.ACTING_USER_HEADER), "the plain instance is unchanged");
    }
}

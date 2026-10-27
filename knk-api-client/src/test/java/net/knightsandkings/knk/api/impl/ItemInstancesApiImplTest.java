package net.knightsandkings.knk.api.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.ApiKeyAuthProvider;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

/** PandiO/knk-workspace#175: what {@code /knk item more} sends to mint copies of an instance item. */
class ItemInstancesApiImplTest {

    private final List<Request> seen = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
        seen.add(chain.request());
        return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(ResponseBody.create("{\"sourceItemInstanceId\":5,\"itemInstanceIds\":[11,12]}", MediaType.get("application/json")))
            .build();
    }).build();
    private final ItemInstancesApiImpl api = new ItemInstancesApiImpl("http://api.test/api", client, new ObjectMapper(),
        new ApiKeyAuthProvider("secret"), executor, false);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    private static String body(Request request) throws Exception {
        Buffer buffer = new Buffer();
        request.body().writeTo(buffer);
        return buffer.readUtf8();
    }

    @Test
    void cloneSendsCountOwnerKeyAndActorAndReadsTheIds() throws Exception {
        assertEquals(List.of(11L, 12L), api.cloneAsync(5, 2, 9, 42).join());

        Request request = seen.get(0);
        assertEquals("POST", request.method());
        assertEquals("http://api.test/api/ItemInstances/5/clone", request.url().toString());
        assertEquals("secret", request.header("X-API-Key"));
        assertEquals("42", request.header(UsersCommandApiImpl.ACTING_USER_HEADER));
        assertEquals("{\"count\":2,\"ownerUserId\":9}", body(request));
    }

    @Test
    void withoutAnActorNoHeader() {
        api.cloneAsync(5, 1, null, null).join();

        assertNull(seen.get(0).header(UsersCommandApiImpl.ACTING_USER_HEADER));
    }
}

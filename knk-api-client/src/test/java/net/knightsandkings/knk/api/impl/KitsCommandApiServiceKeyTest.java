package net.knightsandkings.knk.api.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.auth.ApiKeyAuthProvider;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * KNG-22 / KNG-15: {@code /kit give} used to reach an API route that only took a web JWT, so it
 * always got 401. The API now accepts the plugin's X-API-Key there and records the staff member
 * named in X-Acting-User-Id; this pins what the plugin sends.
 */
class KitsCommandApiServiceKeyTest {

    private static final String GIVE_RESPONSE = "{\"kitId\":7,\"contents\":[]}";

    private final List<Request> seen = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
        seen.add(chain.request());
        return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ResponseBody.create(GIVE_RESPONSE, MediaType.get("application/json"))).build();
    }).build();
    private final KitsCommandApiImpl api = new KitsCommandApiImpl("http://api.test/api", client, new ObjectMapper(),
            new ApiKeyAuthProvider("secret"), executor, false);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void giveSendsTheApiKeyAndTheStaffMember() {
        api.giveAsync(42, 9, 7).join();

        Request request = seen.get(0);
        assertEquals("POST", request.method());
        assertEquals("http://api.test/api/Kits/7/give", request.url().toString());
        assertEquals("secret", request.header("X-API-Key"));
        assertEquals("42", request.header(UsersCommandApiImpl.ACTING_USER_HEADER));
    }

    @Test
    void giveWithoutAKnownSenderSendsNoActor() {
        api.giveAsync(null, 9, 7).join();

        assertEquals("secret", seen.get(0).header("X-API-Key"));
        assertNull(seen.get(0).header(UsersCommandApiImpl.ACTING_USER_HEADER));
    }

    @Test
    void claimSendsTheApiKey() {
        api.claimAsync(9, 7).join();

        assertEquals("secret", seen.get(0).header("X-API-Key"));
    }

    @Test
    void everyClaimSendsItsOwnIdempotencyKey() {
        // KNG-21 Phase 2: the claim's cost is a ledger posting keyed by this header.
        api.claimAsync(9, 7).join();
        api.claimAsync(9, 7).join();

        String first = seen.get(0).header(BaseApiImpl.IDEMPOTENCY_KEY_HEADER);
        String second = seen.get(1).header(BaseApiImpl.IDEMPOTENCY_KEY_HEADER);
        assertNotNull(first);
        assertNotNull(second);
        assertNotEquals(first, second);
    }
}

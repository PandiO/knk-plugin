package net.knightsandkings.knk.api.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.auth.NoAuthProvider;
import net.knightsandkings.knk.core.domain.users.BalanceAdjustmentResult;
import net.knightsandkings.knk.core.domain.users.BalanceChange;
import net.knightsandkings.knk.core.domain.users.BalanceCurrency;
import net.knightsandkings.knk.core.domain.users.BalanceOperation;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * KNG-21 Phase 2: staff balance changes send the mode, never a client-computed delta, with an
 * Idempotency-Key per action, and the result carries the server's own numbers.
 */
class UsersCommandApiBalanceTest {

    private static final String RESPONSE = """
        {"newCoins":1000,"newGems":5,"newExperiencePoints":0,"titleChange":null,"replayed":false,
         "changes":[{"currency":"Coins","mode":"Set","amount":750,"balanceBefore":250,"balanceAfter":1000,
                     "transactionPublicId":"01J0000000000000000000000","replayed":false}]}
        """;

    private final List<Request> seen = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
        Request request = chain.request();
        seen.add(request);
        Buffer buffer = new Buffer();
        request.body().writeTo(buffer);
        bodies.add(buffer.readUtf8());
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ResponseBody.create(RESPONSE, MediaType.get("application/json"))).build();
    }).build();
    private final UsersCommandApiImpl api = new UsersCommandApiImpl("http://api.test/api", client, new ObjectMapper(),
            new NoAuthProvider(), executor, false);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void sendsTheModeAndTheTargetNotADelta() throws Exception {
        api.withActor(42).adjustBalanceById(7, BalanceCurrency.COINS, BalanceOperation.SET, 1000, "reset", true).join();

        Request request = seen.get(0);
        assertEquals("PUT", request.method());
        assertEquals("http://api.test/api/Users/7/balances", request.url().toString());
        assertEquals("42", request.header(UsersCommandApiImpl.ACTING_USER_HEADER));
        JsonNode body = new ObjectMapper().readTree(bodies.get(0));
        JsonNode change = body.get("changes").get(0);
        assertEquals("Coins", change.get("currency").asText());
        assertEquals("Set", change.get("mode").asText());
        assertEquals(1000, change.get("amount").asLong());
        assertFalse(change.has("expectedCurrent"));
        assertEquals("reset", body.get("reason").asText());
    }

    @Test
    void everyActionHasItsOwnIdempotencyKey() {
        api.adjustBalanceById(7, BalanceCurrency.GEMS, BalanceOperation.ADD, 5, "a", true).join();
        api.adjustBalanceById(7, BalanceCurrency.GEMS, BalanceOperation.ADD, 5, "a", true).join();

        String first = seen.get(0).header(BaseApiImpl.IDEMPOTENCY_KEY_HEADER);
        String second = seen.get(1).header(BaseApiImpl.IDEMPOTENCY_KEY_HEADER);
        assertNotNull(first);
        assertNotEquals(first, second);
    }

    @Test
    void theResultCarriesTheServersNumbers() {
        BalanceAdjustmentResult result = api.adjustBalanceById(7, BalanceCurrency.COINS, BalanceOperation.SET, 1000, "reset", true).join();

        BalanceChange change = result.changeFor(BalanceCurrency.COINS);
        assertEquals(BalanceOperation.SET, change.mode());
        assertEquals(750, change.amount());
        assertEquals(250, change.balanceBefore());
        assertEquals(1000, change.balanceAfter());
        assertEquals(1000, result.balanceOf(BalanceCurrency.COINS));
    }
}

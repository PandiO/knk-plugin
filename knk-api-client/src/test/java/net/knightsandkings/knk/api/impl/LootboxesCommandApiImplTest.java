package net.knightsandkings.knk.api.impl;

import net.knightsandkings.knk.api.auth.ApiKeyAuthProvider;
import net.knightsandkings.knk.core.lootbox.KnkLootboxClaimResult;
import net.knightsandkings.knk.core.lootbox.LootboxDeliveryMethod;
import net.knightsandkings.knk.core.lootbox.LootboxRejectedException;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static net.knightsandkings.knk.api.mapper.LootboxMapperTest.apiObjectMapper;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lootboxes Phase 3: what the plugin sends (routes, service key, staff member) and how a refusal comes back: a
 * 409/429 is a {@link LootboxRejectedException} with the API's code, anything else an ordinary failure.
 */
class LootboxesCommandApiImplTest {

    private static final String CLAIM = "{\"claimId\":41,\"userId\":9,\"lootboxTypeId\":3,\"boxStars\":5,"
            + "\"itemBlueprintId\":77,\"quantity\":1,\"itemInstanceId\":5,\"enchantments\":[]}";

    private final List<Request> seen = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private int status = 200;
    private String response = CLAIM;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
        Request request = chain.request();
        seen.add(request);
        Buffer buffer = new Buffer();
        if (request.body() != null) {
            request.body().writeTo(buffer);
        }
        bodies.add(buffer.readUtf8());
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("x")
                .body(ResponseBody.create(response, MediaType.get("application/json"))).build();
    }).build();
    private final LootboxesCommandApiImpl api = new LootboxesCommandApiImpl("http://api.test/api", client, apiObjectMapper(),
            new ApiKeyAuthProvider("secret"), executor, false);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void claim_postsTokenUserAndKey_withTheServiceKey() {
        UUID token = UUID.fromString("00000000-0000-0000-0000-000000000042");

        KnkLootboxClaimResult result = api.claim(12, token, 9, token + ":9").join();

        assertEquals(41, result.claimId());
        assertEquals(5L, result.itemInstanceId());
        Request request = seen.get(0);
        assertEquals("http://api.test/api/LootboxSpawns/12/claim", request.url().toString());
        assertEquals("secret", request.header("X-API-Key"));
        assertNull(request.header(UsersCommandApiImpl.ACTING_USER_HEADER));
        assertTrue(bodies.get(0).contains("\"idempotencyKey\":\"00000000-0000-0000-0000-000000000042:9\""));
        assertTrue(bodies.get(0).contains("\"userId\":9"));
    }

    @Test
    void adminActions_nameTheStaffMember() {
        api.adminGive(42, 9, 3, 5, null).join();

        assertEquals("http://api.test/api/LootboxClaims/admin-give", seen.get(0).url().toString());
        assertEquals("42", seen.get(0).header(UsersCommandApiImpl.ACTING_USER_HEADER));
    }

    @Test
    void conflict_becomesARejectionWithTheCode() {
        status = 409;
        response = "{\"code\":\"AlreadyClaimed\",\"message\":\"Someone else claimed it.\"}";

        CompletionException ex = assertThrows(CompletionException.class,
                () -> api.claim(12, UUID.randomUUID(), 9, "k").join());

        LootboxRejectedException rejected = LootboxRejectedException.find(ex);
        assertNotNull(rejected);
        assertEquals(409, rejected.statusCode());
        assertTrue(rejected.is(LootboxRejectedException.ALREADY_CLAIMED));
        assertEquals("Someone else claimed it.", rejected.getMessage());
    }

    @Test
    void dailyLimit_carriesScopeLimitAndReset() {
        status = 429;
        response = "{\"code\":\"DailyLimit\",\"scope\":\"Type\",\"limit\":2,\"resetsAt\":\"2026-09-27T00:00:00Z\",\"message\":\"x\"}";

        LootboxRejectedException rejected = LootboxRejectedException.find(assertThrows(CompletionException.class,
                () -> api.claim(12, UUID.randomUUID(), 9, "k").join()));

        assertNotNull(rejected);
        assertTrue(rejected.isDailyLimit());
        assertEquals("Type", rejected.scope());
        assertEquals(2, rejected.limit());
        assertEquals(Instant.parse("2026-09-27T00:00:00Z"), rejected.resetsAt());
    }

    @Test
    void serverError_isNotARejection() {
        status = 500;
        response = "boom";

        CompletionException ex = assertThrows(CompletionException.class,
                () -> api.claim(12, UUID.randomUUID(), 9, "k").join());

        assertNull(LootboxRejectedException.find(ex));
    }

    @Test
    void delivered_sendsTheWireMethod() {
        response = "{\"claimId\":41}";

        api.markDelivered(41, LootboxDeliveryMethod.DROPPED_OWNED, "skipped: 3", 9).join();

        assertEquals("http://api.test/api/LootboxClaims/41/delivered", seen.get(0).url().toString());
        assertTrue(bodies.get(0).contains("\"method\":\"DroppedOwned\""));
        assertTrue(bodies.get(0).contains("\"note\":\"skipped: 3\""));
    }

    @Test
    void areaDelete_parsesTheRemovedBoxes() {
        response = "{\"id\":9,\"name\":\"spawn\",\"world\":\"world\",\"wgRegionId\":\"lootbox_spawn\",\"removedSpawnIds\":[3,4]}";

        var result = api.deleteAreaInGame(42, 9).join();

        assertEquals("http://api.test/api/LootboxSpawnAreas/9/in-game-delete", seen.get(0).url().toString());
        assertEquals(List.of(3, 4), result.removedSpawnIds());
        assertEquals("42", seen.get(0).header(UsersCommandApiImpl.ACTING_USER_HEADER));
    }
}

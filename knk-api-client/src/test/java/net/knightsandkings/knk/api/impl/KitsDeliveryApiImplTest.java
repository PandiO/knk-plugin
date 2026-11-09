package net.knightsandkings.knk.api.impl;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.auth.ApiKeyAuthProvider;
import net.knightsandkings.knk.core.domain.item.KnkKitDelivery;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PandiO/knk-workspace#261: the game server reads a player's pending kit deliveries (staff grants made from the web
 * app) and takes each one before placing it. Pins the routes, the plugin key, the loadout mapping, and that a taken
 * delivery (409) or one that isn't pending (404) means "place nothing" while any other error fails.
 */
class KitsDeliveryApiImplTest {

    private static final String PENDING = "[{\"claimId\":501,\"kitId\":10,\"kitName\":\"Knight\",\"grantedAt\":\"2026-11-08T12:00:00Z\","
            + "\"loadout\":{\"kitId\":10,\"helmetId\":5,\"contents\":[{\"slotIndex\":2,\"itemBlueprintId\":7,\"quantity\":3}],"
            + "\"claimId\":501,\"deliveryPending\":true}},"
            + "{\"claimId\":null,\"kitId\":11,\"kitName\":\"Broken\",\"loadout\":null}]";

    private final List<Request> seen = new ArrayList<>();
    private volatile int status = 200;
    private volatile String body = PENDING;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
        seen.add(chain.request());
        return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("x")
                .body(ResponseBody.create(body, MediaType.get("application/json"))).build();
    }).build();
    private final KitsCommandApiImpl api = new KitsCommandApiImpl("http://api.test/api", client,
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false),
            new ApiKeyAuthProvider("secret"), executor, false);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void pendingDeliveriesAreReadWithTheKey_AndUnplaceableOnesAreDropped() {
        List<KnkKitDelivery> pending = api.pendingDeliveriesAsync(31).join();

        Request request = seen.get(0);
        assertEquals("GET", request.method());
        assertEquals("http://api.test/api/Kits/deliveries/pending?userId=31", request.url().toString());
        assertEquals("secret", request.header("X-API-Key"));
        assertEquals(1, pending.size());
        KnkKitDelivery delivery = pending.get(0);
        assertEquals(501, delivery.claimId());
        assertEquals("Knight", delivery.kitName());
        assertEquals(5, delivery.loadout().helmetId());
        assertEquals(7, delivery.loadout().contents().get(0).itemBlueprintId());
    }

    @Test
    void markDelivered_TakenByThisCall_IsTrue() {
        body = "{\"claimId\":501,\"delivered\":true}";

        assertTrue(api.markDeliveredAsync(31, 501).join());

        Request request = seen.get(0);
        assertEquals("POST", request.method());
        assertEquals("http://api.test/api/Kits/deliveries/501/delivered?userId=31", request.url().toString());
        assertEquals("secret", request.header("X-API-Key"));
    }

    @Test
    void markDelivered_TakenBeforeOrNotPending_IsFalse() {
        body = "{\"code\":\"AlreadyDelivered\"}";
        status = 409;
        assertFalse(api.markDeliveredAsync(31, 501).join());
        status = 404;
        assertFalse(api.markDeliveredAsync(31, 501).join());
    }

    @Test
    void markDelivered_AnyOtherError_Fails() {
        body = "{}";
        status = 500;
        assertThrows(CompletionException.class, () -> api.markDeliveredAsync(31, 501).join());
    }
}

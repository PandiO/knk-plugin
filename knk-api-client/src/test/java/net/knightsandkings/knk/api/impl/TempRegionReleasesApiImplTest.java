package net.knightsandkings.knk.api.impl;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.auth.ApiKeyAuthProvider;
import net.knightsandkings.knk.core.regions.TempRegionRelease;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PandiO/knk-workspace#269: the routes, the plugin key, the mapping, and that a settled-already release (404) is no error. */
class TempRegionReleasesApiImplTest {

    private final List<Request> seen = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private volatile int status = 200;
    private volatile String body = "[{\"id\":7,\"worldTaskId\":97,\"regionId\":\"tempregion_worldtask_97\",\"world\":\"knk_hub\","
            + "\"reason\":\"DraftDeleted\",\"requestedAt\":\"2026-11-09T12:00:00\"},"
            + "{\"id\":8,\"worldTaskId\":98,\"regionId\":\"tempregion_worldtask_98\",\"world\":null,\"reason\":\"TaskExpired\"},"
            + "{\"id\":0,\"worldTaskId\":99,\"regionId\":null}]";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
        seen.add(chain.request());
        if (chain.request().body() != null) {
            Buffer buffer = new Buffer();
            chain.request().body().writeTo(buffer);
            bodies.add(buffer.readUtf8());
        }
        return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("x")
                .body(ResponseBody.create(status == 204 ? "" : body, MediaType.get("application/json"))).build();
    }).build();
    private final TempRegionReleasesApiImpl api = new TempRegionReleasesApiImpl("http://api.test/api", client,
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false),
            new ApiKeyAuthProvider("secret"), executor, false);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void listDue_readsTheReleasesWithTheKey_andDropsBrokenRows() {
        List<TempRegionRelease> due = api.listDue("survival 1").join();

        Request request = seen.get(0);
        assertEquals("GET", request.method());
        assertEquals("http://api.test/api/WorldTasks/temp-region-releases?serverId=survival+1", request.url().toString());
        assertEquals("secret", request.header("X-API-Key"));
        assertEquals(List.of(new TempRegionRelease(7, 97, "tempregion_worldtask_97", "knk_hub", "DraftDeleted"),
                new TempRegionRelease(8, 98, "tempregion_worldtask_98", null, "TaskExpired")), due);
    }

    @Test
    void settle_postsTheOutcome_404MeansSettledAlready_otherErrorsFail() {
        status = 204;
        assertTrue(api.settle(7, TempRegionRelease.Outcome.REMOVED, "survival-1", "REMOVE").join());
        assertEquals("POST", seen.get(0).method());
        assertEquals("http://api.test/api/WorldTasks/temp-region-releases/7/settle", seen.get(0).url().toString());
        assertTrue(bodies.get(0).contains("\"outcome\":\"Removed\""));
        assertTrue(bodies.get(0).contains("\"serverId\":\"survival-1\""));

        status = 404;
        body = "{}";
        assertFalse(api.settle(7, TempRegionRelease.Outcome.MISSING, null, null).join());
        assertFalse(bodies.get(1).contains("serverId"));

        status = 500;
        assertThrows(CompletionException.class, () -> api.settle(8, TempRegionRelease.Outcome.KEPT, "s", "x").join());
    }
}

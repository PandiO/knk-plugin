package net.knightsandkings.knk.api.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.auth.NoAuthProvider;
import net.knightsandkings.knk.core.domain.settings.KnkGameSettings;
import net.knightsandkings.knk.core.domain.settings.KnkSpawnReference;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GET /api/GameSettings for /spawn (docs/specs/teleport/DESIGN.md §3.6, Phase 4). */
class GameSettingsQueryApiImplTest {

    private final List<Request> seen = new ArrayList<>();
    private String responseJson = "{}";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
        Request request = chain.request();
        seen.add(request);
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ResponseBody.create(responseJson, MediaType.get("application/json"))).build();
    }).build();
    // The shared client's mapper does not fail on unknown properties either; this one relies on the DTO's own annotation.
    private final GameSettingsQueryApiImpl api = new GameSettingsQueryApiImpl("http://api.test/api", client,
            new ObjectMapper(), new NoAuthProvider(), executor, false);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void readsTheJoinSpawnReference() {
        responseJson = """
                {"id":"global","settingsVersion":"1","joinAnnouncement":"&a{player} joined",
                 "joinSpawnMode":"CustomReference",
                 "joinSpawnReference":{"sourceType":"Town","sourceId":4,
                   "displayLabel":"Town: Kardenna (world 10, 64, -3)",
                   "location":{"locationId":12,"name":"Kardenna","x":10.5,"y":64,"z":-3.5,"yaw":90,"pitch":0,"world":"world"}},
                 "defaultRespawnPolicy":{"mode":"WorldSpawn"},"worldSettings":[{"worldName":"world"}],"runtimeWorlds":[]}
                """;

        KnkGameSettings settings = api.get().join();

        Request request = seen.get(0);
        assertEquals("GET", request.method());
        assertEquals("http://api.test/api/GameSettings", request.url().toString());
        KnkSpawnReference reference = settings.customJoinSpawn().orElseThrow();
        assertEquals(KnkSpawnReference.SourceType.TOWN, reference.sourceType());
        assertEquals(4, reference.sourceId());
        assertEquals("Town: Kardenna (world 10, 64, -3)", reference.label());
        assertEquals("world", reference.snapshot().world());
        assertEquals(10.5, reference.snapshot().x());
        assertEquals(-3.5, reference.snapshot().z());
        assertEquals(90f, reference.snapshot().yaw());
        assertEquals(12, reference.snapshot().id());
    }

    @Test
    void worldSpawnModeHasNoCustomSpawn() {
        responseJson = "{\"joinSpawnMode\":\"WorldSpawn\",\"joinSpawnReference\":null}";

        KnkGameSettings settings = api.get().join();

        assertEquals("WorldSpawn", settings.joinSpawnMode());
        assertTrue(settings.customJoinSpawn().isEmpty());
    }

    @Test
    void unknownSourceTypeAndMissingSnapshotAreKeptAsNull() {
        responseJson = "{\"joinSpawnMode\":\"CustomReference\",\"joinSpawnReference\":{\"sourceType\":\"Street\",\"sourceId\":2}}";

        KnkSpawnReference reference = api.get().join().customJoinSpawn().orElseThrow();

        assertNull(reference.sourceType());
        assertNull(reference.snapshot());
        assertEquals("Location #2", reference.label());
    }
}

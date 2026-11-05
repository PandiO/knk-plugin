package net.knightsandkings.knk.paper.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.realtime.PrivacyEvents;
import net.knightsandkings.knk.api.realtime.RealtimeEnvelope;
import net.knightsandkings.knk.core.privacy.AccountErasureService;
import net.knightsandkings.knk.core.privacy.ErasedAccount;
import net.knightsandkings.knk.core.privacy.ErasureCursorStore;
import net.knightsandkings.knk.core.privacy.ErasureFeedPage;
import net.knightsandkings.knk.core.privacy.LocalDataEraser;

/**
 * PandiO/knk-workspace#184: {@code AccountErased} from the hub reaches the erasure service through the dispatcher, a
 * resync runs the catch-up, and an unusable payload changes nothing.
 */
class ErasureRealtimeSyncTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @TempDir
    Path dir;

    private final List<String> erased = new CopyOnWriteArrayList<>();
    private final List<Long> asked = new CopyOnWriteArrayList<>();

    private ErasureRealtimeSync sync(RealtimeDispatcher dispatcher) {
        LocalDataEraser eraser = new LocalDataEraser();
        eraser.register("offline store", LocalDataEraser.Where.WORKER, a -> { erased.add(a.erasureId() + ":" + a.formerUuid()); return 1; });
        AccountErasureService service = new AccountErasureService(eraser, (since, limit) -> {
            asked.add(since);
            return CompletableFuture.completedFuture(new ErasureFeedPage(List.of(new ErasedAccount(3, 42, PLAYER)), 3, false, 3));
        }, new ErasureCursorStore(dir.resolve("erasure-feed.json"), Logger.getAnonymousLogger()),
            account -> CompletableFuture.completedFuture(false), Runnable::run, Runnable::run, Duration.ZERO, d -> { },
            Logger.getAnonymousLogger());
        ErasureRealtimeSync sync = new ErasureRealtimeSync(service, Logger.getAnonymousLogger());
        sync.register(dispatcher);
        return sync;
    }

    private static RealtimeEnvelope envelope(String payload) {
        return RealtimeEnvelope.parse("{\"v\":1,\"seq\":2,\"type\":\"AccountErased\",\"at\":\"2026-11-05T20:00:00Z\","
            + "\"actor\":null,\"payload\":" + payload + "}", new ObjectMapper());
    }

    @Test
    void aPush_IsApplied_AndThePositionCaughtUp() {
        RealtimeDispatcher dispatcher = new RealtimeDispatcher(Logger.getAnonymousLogger());
        sync(dispatcher);

        assertTrue(dispatcher.types().contains(PrivacyEvents.ACCOUNT_ERASED));
        assertTrue(dispatcher.dispatch(envelope("{\"userId\":42,\"formerUuid\":\"" + PLAYER + "\",\"erasureId\":3}")));

        assertEquals(List.of("3:" + PLAYER), erased); // the push; the catch-up finds #3 again and skips it
        assertEquals(List.of(0L), asked);
    }

    @Test
    void aResync_CatchesUp() {
        ErasureRealtimeSync sync = sync(new RealtimeDispatcher(Logger.getAnonymousLogger()));

        sync.resync();

        assertEquals(List.of("3:" + PLAYER), erased);
    }

    @Test
    void anUnusablePayload_IsIgnored() {
        RealtimeDispatcher dispatcher = new RealtimeDispatcher(Logger.getAnonymousLogger());
        sync(dispatcher);

        dispatcher.dispatch(envelope("{\"userId\":42}"));

        assertTrue(erased.isEmpty());
        assertTrue(asked.isEmpty());
    }
}

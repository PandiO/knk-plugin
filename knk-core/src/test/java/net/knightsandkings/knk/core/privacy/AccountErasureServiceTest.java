package net.knightsandkings.knk.core.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.knightsandkings.knk.core.ports.api.PrivacyErasuresApi;
import net.knightsandkings.knk.core.privacy.LocalDataEraser.Where;

class AccountErasureServiceTest {

    private static final UUID ONLINE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OFFLINE = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    @TempDir
    Path dir;

    /** The API's feed: rows by id; fails while {@code down}. */
    private static final class FakeFeed implements PrivacyErasuresApi {
        final List<ErasedAccount> rows = new ArrayList<>();
        final List<Long> asked = new CopyOnWriteArrayList<>();
        volatile boolean down;
        long latestOverride = -1;

        @Override
        public CompletableFuture<ErasureFeedPage> since(long sinceId, int limit) {
            asked.add(sinceId);
            if (down) {
                return CompletableFuture.failedFuture(new RuntimeException("Connection refused"));
            }
            List<ErasedAccount> after = rows.stream().filter(r -> r.erasureId() > sinceId).limit(limit + 1L).toList();
            List<ErasedAccount> page = after.stream().limit(limit).toList();
            long latest = latestOverride >= 0 ? latestOverride : rows.stream().mapToLong(ErasedAccount::erasureId).max().orElse(0);
            return CompletableFuture.completedFuture(new ErasureFeedPage(page,
                page.isEmpty() ? sinceId : page.get(page.size() - 1).erasureId(), after.size() > limit, latest));
        }
    }

    /** Runs tasks at once and records that it did (the "main thread"). */
    private static final class MainThread implements Executor {
        int ran;

        @Override
        public void execute(Runnable command) {
            ran++;
            command.run();
        }
    }

    private final FakeFeed feed = new FakeFeed();
    private final MainThread main = new MainThread();
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final List<Duration> slept = new ArrayList<>();
    private LocalDataEraser eraser;
    private ErasureCursorStore cursor;

    @BeforeEach
    void setUp() {
        eraser = new LocalDataEraser();
        eraser.register("siege vault", Where.MAIN_THREAD, a -> { events.add("vault " + a.erasureId()); return 1; });
        eraser.register("offline store", Where.WORKER, a -> { events.add("offline " + a.erasureId()); return a.formerUuid() == null ? 0 : 2; });
        cursor = new ErasureCursorStore(dir.resolve("erasure-feed.json"), Logger.getAnonymousLogger());
    }

    private AccountErasureService service() {
        AccountErasureService.Kicker kicker = account -> {
            boolean online = ONLINE.equals(account.formerUuid());
            if (online) {
                events.add("kick " + account.erasureId());
            }
            return CompletableFuture.completedFuture(online);
        };
        return new AccountErasureService(eraser, feed, cursor, kicker, main, Runnable::run, Duration.ofSeconds(2),
            slept::add, Logger.getAnonymousLogger());
    }

    @Test
    void anOnlinePlayer_IsKickedFirst_ThenTheStepsRun_MainThreadOnesOnTheMainThread() {
        AccountErasureService.Report report = service().apply(new ErasedAccount(5, 42, ONLINE));

        assertEquals(List.of("kick 5", "vault 5", "offline 5"), events);
        assertEquals(List.of(Duration.ofSeconds(2)), slept);
        assertEquals(1, main.ran);
        assertTrue(report.kicked());
        assertEquals(Map.of("siege vault", 1, "offline store", 2), report.erased());
        assertEquals(3, report.total());
    }

    @Test
    void anOfflinePlayer_IsNotKicked_AndNothingWaits() {
        AccountErasureService.Report report = service().apply(new ErasedAccount(6, 43, OFFLINE));

        assertFalse(report.kicked());
        assertTrue(slept.isEmpty());
        assertEquals(List.of("vault 6", "offline 6"), events);
    }

    @Test
    void aPush_IsApplied_ThenTheCatchUpMovesThePosition_WithoutApplyingItTwice() {
        feed.rows.add(new ErasedAccount(1, 40, OFFLINE));
        feed.rows.add(new ErasedAccount(2, 41, ONLINE));
        AccountErasureService service = service();

        service.pushed(new ErasedAccount(2, 41, ONLINE));

        // The push first; the catch-up then finds #1 (missed earlier) and skips #2.
        assertEquals(List.of("kick 2", "vault 2", "offline 2", "vault 1", "offline 1"), events);
        assertEquals(2, cursor.lastId());
        assertEquals(List.of(0L), feed.asked);
        assertEquals(2, service.appliedCount());
    }

    @Test
    void catchUp_PagesFromTheStoredPosition() {
        for (long id = 1; id <= 250; id++) {
            feed.rows.add(new ErasedAccount(id, (int) id, null));
        }
        cursor.advanceTo(20);

        assertEquals(230, service().catchUpNow());

        assertEquals(250, cursor.lastId());
        assertEquals(List.of(20L, 120L, 220L), feed.asked);
        assertFalse(events.contains("offline 20"));
        assertTrue(events.contains("offline 21"));
    }

    @Test
    void apiDown_StopsWithoutMovingThePosition() {
        feed.rows.add(new ErasedAccount(1, 40, OFFLINE));
        feed.down = true;

        assertEquals(0, service().catchUpNow());

        assertEquals(0, cursor.lastId());
        assertTrue(events.isEmpty());
    }

    @Test
    void aPositionPastTheFeed_StartsAgainFromZero() {
        cursor.advanceTo(500);
        feed.rows.add(new ErasedAccount(1, 40, OFFLINE));

        assertEquals(1, service().catchUpNow());

        assertEquals(1, cursor.lastId());
        assertEquals(List.of(500L, 0L), feed.asked);
    }

    @Test
    void anEmptyFeed_AtZero_IsNotAReset() {
        assertEquals(0, service().catchUpNow());
        assertEquals(List.of(0L), feed.asked);
    }

    @Test
    void aFailedKick_StillDeletes() {
        AccountErasureService service = new AccountErasureService(eraser, feed, cursor,
            account -> CompletableFuture.failedFuture(new IllegalStateException("plugin disabled")), main, Runnable::run,
            Duration.ofSeconds(2), slept::add, Logger.getAnonymousLogger());

        AccountErasureService.Report report = service.apply(new ErasedAccount(7, 44, ONLINE));

        assertFalse(report.kicked());
        assertEquals(List.of("vault 7", "offline 7"), events);
    }

    @Test
    void sameErasureTwiceInOneRun_IsAppliedOnce() {
        AccountErasureService service = service();
        service.apply(new ErasedAccount(8, 45, OFFLINE));

        assertNull(service.apply(new ErasedAccount(8, 45, OFFLINE)));
        assertEquals(List.of("vault 8", "offline 8"), events);
    }

    @Test
    void theLogLine_NamesTheErasureIdOnly() {
        String line = AccountErasureService.describe(new AccountErasureService.Report(9, true,
            Map.of("offline store", 2, "siege vault", 0), List.of("statistics spool")));

        assertTrue(line.contains("Erasure #9") && line.contains("player kicked") && line.contains("offline store 2")
            && line.contains("failed: statistics spool"), line);
        assertFalse(line.contains("siege vault"), line);
        assertFalse(line.contains(ONLINE.toString()), line);
    }

    @Test
    void closed_IgnoresNewWork() {
        AccountErasureService service = service();
        service.close();

        service.pushed(new ErasedAccount(10, 46, ONLINE));
        service.catchUp();

        assertTrue(events.isEmpty());
        assertTrue(feed.asked.isEmpty());
    }

    @Test
    void aUuidThatBelongsToANewerAccount_IsLeftAlone_OnlyTheOldUserIdIsErased() {
        AccountErasureService service = service();
        service.setKnownUserIds(uuid -> ONLINE.equals(uuid) ? 99 : null);

        AccountErasureService.Report report = service.apply(new ErasedAccount(11, 42, ONLINE));

        assertFalse(report.kicked());
        assertEquals(List.of("vault 11", "offline 11"), events);
        assertEquals(0, report.erased().get("offline store")); // the step saw no UUID
    }

    @Test
    void theSameUserId_OrAnUnknownUuid_KeepsTheUuid() {
        AccountErasureService service = service();
        service.setKnownUserIds(uuid -> ONLINE.equals(uuid) ? 42 : null);

        assertTrue(service.apply(new ErasedAccount(12, 42, ONLINE)).kicked());
        assertEquals(2, service.apply(new ErasedAccount(13, 43, OFFLINE)).erased().get("offline store"));
    }
}

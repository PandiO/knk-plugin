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
    void aFailedStep_KeepsThePosition_AndTheNextCatchUpRunsItAgain() {
        int[] spoolCalls = {0};
        eraser.register("statistics spool", Where.WORKER, a -> {
            if (a.erasureId() == 1 && spoolCalls[0]++ == 0) {
                throw new IllegalStateException("disk full");
            }
            return 1;
        });
        feed.rows.add(new ErasedAccount(1, 40, OFFLINE));
        feed.rows.add(new ErasedAccount(2, 41, OFFLINE));
        AccountErasureService service = service();

        service.catchUpNow();

        // #1 failed a step: the position stays before it and #2 waits behind it.
        assertEquals(0, cursor.lastId());
        assertEquals(List.of("vault 1", "offline 1"), events);

        service.catchUpNow();

        // The next catch-up runs #1 again (now complete), then #2.
        assertEquals(2, cursor.lastId());
        assertEquals(List.of("vault 1", "offline 1", "vault 1", "offline 1", "vault 2", "offline 2"), events);
    }

    @Test
    void anErasureWithAFailedStep_IsNotRememberedAsApplied() {
        eraser.register("statistics spool", Where.WORKER, a -> { throw new IllegalStateException("disk full"); });
        AccountErasureService service = service();

        AccountErasureService.Report first = service.apply(new ErasedAccount(3, 46, OFFLINE));
        AccountErasureService.Report again = service.apply(new ErasedAccount(3, 46, OFFLINE));

        assertEquals(List.of("statistics spool"), first.failed());
        assertEquals(List.of("statistics spool"), again.failed());
        assertEquals(List.of("vault 3", "offline 3", "vault 3", "offline 3"), events);
    }

    @Test
    void aFeedReset_ForgetsWhatThisRunApplied_SoTheNewFeedsIdsAreApplied() {
        AccountErasureService service = service();
        service.apply(new ErasedAccount(1, 40, OFFLINE)); // the old feed's #1, applied in this run
        cursor.advanceTo(500);
        feed.rows.add(new ErasedAccount(1, 77, null)); // the new database's #1: another account

        assertEquals(1, service.catchUpNow());

        assertEquals(List.of("vault 1", "offline 1", "vault 1", "offline 1"), events);
        assertEquals(1, cursor.lastId());
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

    /** Decision 2026-11-06 on LR1-8: what the kicker gave back before the kick is in the report and the log, as counts. */
    @Test
    void whatWasGivenBackBeforeTheKick_IsReported_AsCountsOnly() {
        AccountErasureService.Kicker kicker = new AccountErasureService.Kicker() {
            @Override
            public CompletableFuture<Boolean> kick(ErasedAccount account) {
                throw new AssertionError("the service asks for returnAndKick");
            }

            @Override
            public CompletableFuture<AccountErasureService.Kick> returnAndKick(ErasedAccount account) {
                events.add("return+kick " + account.erasureId());
                return CompletableFuture.completedFuture(
                    new AccountErasureService.Kick(true, Map.of("siege vault items", 12), List.of()));
            }
        };
        AccountErasureService service = new AccountErasureService(eraser, feed, cursor, kicker, main, Runnable::run,
            Duration.ofSeconds(2), slept::add, Logger.getAnonymousLogger());

        AccountErasureService.Report report = service.apply(new ErasedAccount(14, 42, ONLINE));

        assertEquals(List.of("return+kick 14", "vault 14", "offline 14"), events);
        assertTrue(report.kicked());
        assertEquals(Map.of("siege vault items", 12), report.returned());
        assertEquals(3, report.total()); // deletions only
        String line = AccountErasureService.describe(report);
        assertTrue(line.contains("given back before the kick: siege vault items 12") && line.contains("player kicked"), line);
        assertFalse(line.contains(ONLINE.toString()), line);
    }

    @Test
    void aReturnThatFailed_IsListedWithTheFailedSteps_AndTheDeletionStillRuns() {
        AccountErasureService.Kicker kicker = new AccountErasureService.Kicker() {
            @Override
            public CompletableFuture<Boolean> kick(ErasedAccount account) {
                return CompletableFuture.completedFuture(true);
            }

            @Override
            public CompletableFuture<AccountErasureService.Kick> returnAndKick(ErasedAccount account) {
                return CompletableFuture.completedFuture(
                    new AccountErasureService.Kick(true, Map.of(), List.of("siege vault items")));
            }
        };
        AccountErasureService service = new AccountErasureService(eraser, feed, cursor, kicker, main, Runnable::run,
            Duration.ofSeconds(2), slept::add, Logger.getAnonymousLogger());

        AccountErasureService.Report report = service.apply(new ErasedAccount(15, 42, ONLINE));

        assertEquals(List.of("siege vault items"), report.failed());
        assertEquals(List.of("vault 15", "offline 15"), events);
    }

    @Test
    void aKickerWithoutReturns_KicksOnly() {
        AccountErasureService.Kicker kicker = account -> CompletableFuture.completedFuture(true);

        AccountErasureService.Kick kick = kicker.returnAndKick(new ErasedAccount(16, 42, ONLINE)).join();

        assertTrue(kick.kicked());
        assertTrue(kick.returned().isEmpty() && kick.failed().isEmpty());
    }

    // --- #486: the catch-up path and the returning player's first join ---

    private ErasedPlayerMarks marksWithStep() {
        ErasedPlayerMarks marks = new ErasedPlayerMarks(dir.resolve("erasure-first-joins.json"), null, Logger.getAnonymousLogger());
        eraser.register("next join is a first join", Where.WORKER, a -> a.formerUuid() == null ? 0 : marks.mark(a.formerUuid()));
        return marks;
    }

    /**
     * § S5 step 5: the server was down while T2 was erased; after the start T2 connects. The join waits for the
     * catch-up, which applies the erasure with the UUID and marks T2's next join as a first join.
     */
    @Test
    void catchUpAfterARestart_AppliesTheErasureBeforeTheJoin_AndMarksTheFirstJoin() {
        feed.rows.add(new ErasedAccount(1, 40, ONLINE));
        cursor.advanceTo(1); // applied before the stop
        feed.rows.add(new ErasedAccount(2, 41, OFFLINE)); // filed while the server was down
        ErasedPlayerMarks marks = marksWithStep();
        AccountErasureService afterStart = service();
        assertFalse(afterStart.isCaughtUp());

        assertTrue(afterStart.awaitCaughtUp(Duration.ofSeconds(5)));

        assertEquals(2, cursor.lastId());
        assertEquals(List.of("vault 2", "offline 2"), events);
        assertTrue(marks.take(OFFLINE), "T2's join is a first join");
        assertFalse(marks.take(ONLINE), "the erasure before the stop isn't applied again");
    }

    @Test
    void anErasureAppliedByThePush_MarksTheFirstJoinToo() {
        ErasedPlayerMarks marks = marksWithStep();
        feed.rows.add(new ErasedAccount(3, 42, ONLINE));

        service().pushed(new ErasedAccount(3, 42, ONLINE));

        assertTrue(marks.isMarked(ONLINE));
    }

    @Test
    void anErasureWithoutTheUuid_MarksNobody() {
        ErasedPlayerMarks marks = marksWithStep();
        feed.rows.add(new ErasedAccount(4, 43, null));

        service().awaitCaughtUp(Duration.ofSeconds(5));

        assertEquals(0, marks.size());
    }

    @Test
    void onceCaughtUp_JoinsDoNotAskTheApiAgain() {
        AccountErasureService service = service();
        assertTrue(service.awaitCaughtUp(Duration.ofSeconds(5)));

        assertTrue(service.awaitCaughtUp(Duration.ofSeconds(5)));
        assertTrue(service.awaitCaughtUp(Duration.ofSeconds(5)));

        assertEquals(List.of(0L), feed.asked);
    }

    @Test
    void theApiDown_TheJoinGoesOn_AndTheNextJoinTriesAgain() {
        feed.rows.add(new ErasedAccount(1, 40, OFFLINE));
        feed.down = true;
        AccountErasureService service = service();

        assertFalse(service.awaitCaughtUp(Duration.ofSeconds(5)));
        assertTrue(events.isEmpty());

        feed.down = false;
        assertTrue(service.awaitCaughtUp(Duration.ofSeconds(5)));
        assertEquals(List.of("vault 1", "offline 1"), events);
    }

    @Test
    void aSlowApi_HoldsTheJoinAtMostTheWait() {
        PrivacyErasuresApi hanging = (since, limit) -> new CompletableFuture<>();
        AccountErasureService service = new AccountErasureService(eraser, hanging, cursor,
            account -> CompletableFuture.completedFuture(false), main, null, Duration.ZERO, slept::add,
            Logger.getAnonymousLogger());
        try {
            long start = System.nanoTime();
            assertFalse(service.awaitCaughtUp(Duration.ofMillis(200)));
            long waitedMs = (System.nanoTime() - start) / 1_000_000;
            assertTrue(waitedMs < 5_000, "waited " + waitedMs + " ms");
            assertFalse(service.isCaughtUp());
        } finally {
            service.close();
        }
    }

    @Test
    void closed_JoinsDoNotWait() {
        AccountErasureService service = service();
        service.close();

        assertFalse(service.awaitCaughtUp(Duration.ofSeconds(5)));
        assertTrue(feed.asked.isEmpty());
    }
}

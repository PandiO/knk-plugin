package net.knightsandkings.knk.core.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.knightsandkings.knk.core.domain.messaging.PrivateMessageLogEntry;
import net.knightsandkings.knk.core.domain.messaging.PrivateMessageLogEntry.Outcome;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.PrivateMessageLogApi;

/** KNG-18 Phase 3: batching, overflow, retry with the same ids, backoff and the restart spool. */
class PrivateMessageLogShipperTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @TempDir
    Path dir;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-26T12:00:00Z"));
    private final FakeApi api = new FakeApi();
    private final List<PrivateMessageLogShipper> shippers = new ArrayList<>();

    @AfterEach
    void closeAll() {
        shippers.forEach(PrivateMessageLogShipper::close);
    }

    /** Status to fail the next sends with; 0 = succeed; -1 = connection error; -2 = never answers. */
    private static final class FakeApi implements PrivateMessageLogApi {
        final List<List<PrivateMessageLogEntry>> batches = new CopyOnWriteArrayList<>();
        volatile int failWith;

        @Override
        public CompletableFuture<BatchResult> submitBatch(List<PrivateMessageLogEntry> entries) {
            batches.add(List.copyOf(entries));
            if (failWith == -2) {
                return new CompletableFuture<>();
            }
            if (failWith == -1) {
                return CompletableFuture.failedFuture(new RuntimeException("Failed to send",
                        new java.net.ConnectException("Connection refused")));
            }
            if (failWith != 0) {
                return CompletableFuture.failedFuture(new RuntimeException("Failed to send",
                        new ApiException("http://api/private-message-log/batch", failWith, "Request failed", "{}")));
            }
            return CompletableFuture.completedFuture(new BatchResult(entries.size(), 0));
        }

        List<String> sentTexts() {
            return batches.stream().flatMap(List::stream).map(PrivateMessageLogEntry::content).toList();
        }
    }

    private PrivateMessageLogShipper shipper(int capacity, Path spool) {
        // A long interval: tests drive sending with flushNow().
        PrivateMessageLogShipper shipper = new PrivateMessageLogShipper(api, spool, capacity, 50, Duration.ofSeconds(5), clock);
        shippers.add(shipper);
        return shipper;
    }

    private static PrivateMessageLogEntry pm(String text) {
        return new PrivateMessageLogEntry(UUID.randomUUID(), Instant.parse("2026-09-26T11:59:00Z"), ALICE, "Alice",
                BOB, "Bob", text, Outcome.DELIVERED, false);
    }

    @Test
    void fiftyWaiting_SendsWithoutWaitingForTheTimer() throws Exception {
        PrivateMessageLogShipper shipper = shipper(1000, null);
        IntStream.range(0, 49).forEach(i -> shipper.submit(pm("m" + i)));
        shipper.awaitIdle();
        assertTrue(api.batches.isEmpty(), "fewer than 50 wait for the timer");

        shipper.submit(pm("m49"));
        shipper.awaitIdle();

        assertEquals(List.of(50), api.batches.stream().map(List::size).toList());
        assertEquals(0, shipper.queueDepth());
    }

    @Test
    void sendsEverything_InOrder_InBatchesOfAtMostFifty() throws Exception {
        PrivateMessageLogShipper shipper = shipper(1000, null);
        IntStream.range(0, 120).forEach(i -> shipper.submit(pm("m" + i)));
        shipper.flushNow();

        assertTrue(api.batches.stream().allMatch(b -> !b.isEmpty() && b.size() <= 50));
        assertEquals(IntStream.range(0, 120).mapToObj(i -> "m" + i).toList(), api.sentTexts());
        assertEquals(0, shipper.queueDepth());
    }

    @Test
    void fullQueue_DropsTheOldest() {
        PrivateMessageLogShipper shipper = shipper(5, null);
        api.failWith = 503;
        IntStream.range(0, 8).forEach(i -> shipper.submit(pm("m" + i)));

        assertEquals(5, shipper.queueDepth());
        assertEquals(3, shipper.droppedCount());
    }

    @Test
    void failedBatch_IsRetriedWithTheSameIds_AfterABackoff() throws Exception {
        PrivateMessageLogShipper shipper = shipper(1000, null);
        shipper.submit(pm("one"));
        shipper.submit(pm("two"));
        api.failWith = 500;

        shipper.flushNow();
        assertEquals(2, shipper.queueDepth());

        api.failWith = 0;
        shipper.flushNow();
        assertEquals(1, api.batches.size(), "still backing off");

        clock.advance(Duration.ofSeconds(5));
        shipper.flushNow();
        assertEquals(2, api.batches.size());
        assertEquals(api.batches.get(0), api.batches.get(1), "same entries, same client message ids");
        assertEquals(0, shipper.queueDepth());
    }

    @Test
    void connectionErrors_AreRetriedToo() throws Exception {
        PrivateMessageLogShipper shipper = shipper(1000, null);
        shipper.submit(pm("one"));
        api.failWith = -1;
        shipper.flushNow();
        assertEquals(1, shipper.queueDepth());
    }

    @Test
    void wrongKey_IsRetried_ButAMalformedBatchIsDropped() throws Exception {
        PrivateMessageLogShipper shipper = shipper(1000, null);
        shipper.submit(pm("one"));
        api.failWith = 401;
        shipper.flushNow();
        assertEquals(1, shipper.queueDepth());

        clock.advance(Duration.ofMinutes(5));
        api.failWith = 400;
        shipper.flushNow();
        assertEquals(0, shipper.queueDepth());
    }

    @Test
    void backoff_DoublesUpToAMinute() {
        PrivateMessageLogShipper shipper = shipper(1000, null);
        assertEquals(Duration.ofSeconds(5), shipper.backoff(1));
        assertEquals(Duration.ofSeconds(10), shipper.backoff(2));
        assertEquals(Duration.ofSeconds(40), shipper.backoff(4));
        assertEquals(Duration.ofMinutes(1), shipper.backoff(5));
        assertEquals(Duration.ofMinutes(1), shipper.backoff(50));
    }

    @Test
    void apiDownAtShutdown_SpoolsAndResendsOnNextStart() throws Exception {
        Path spool = dir.resolve("pm-log-spool.jsonl");
        PrivateMessageLogShipper first = shipper(1000, spool);
        first.start();
        PrivateMessageLogEntry console = new PrivateMessageLogEntry(UUID.randomUUID(), Instant.parse("2026-09-26T11:00:00Z"),
                null, "CONSOLE", ALICE, "Alice", "hi \"there\"\nsecond line", Outcome.BLOCKED_IGNORED, true);
        first.submit(pm("one"));
        first.submit(console);
        api.failWith = 503;
        first.flushNow();
        first.close();
        assertTrue(Files.exists(spool));

        api.batches.clear();
        api.failWith = 0;
        PrivateMessageLogShipper second = shipper(1000, spool);
        second.start();
        second.awaitIdle();
        assertEquals(2, second.queueDepth());
        assertFalse(Files.exists(spool), "loaded spool is removed");

        second.flushNow();
        assertEquals("one", api.batches.get(0).get(0).content());
        assertEquals(console, api.batches.get(0).get(1));
    }

    @Test
    void sendHangingAtShutdown_StillSpoolsEverything() throws Exception {
        Path spool = dir.resolve("pm-log-spool.jsonl");
        PrivateMessageLogShipper first = shipper(1000, spool);
        first.closeWait(Duration.ofMillis(300));
        first.start();
        api.failWith = -2;
        IntStream.range(0, 50).forEach(i -> first.submit(pm("m" + i)));  // a send starts and hangs
        first.submit(pm("late"));

        first.close();

        assertTrue(Files.exists(spool), "spooled although the close task never ran");
        api.failWith = 0;
        api.batches.clear();
        PrivateMessageLogShipper second = shipper(1000, spool);
        second.start();
        second.awaitIdle();
        assertEquals(51, second.queueDepth(), "the hanging batch and the one behind it");
        second.flushNow();
        assertEquals("m0", api.sentTexts().get(0));
        assertEquals("late", api.sentTexts().get(50));
    }

    @Test
    void apiUpAtShutdown_SendsTheRest_AndLeavesNoSpool() throws Exception {
        Path spool = dir.resolve("pm-log-spool.jsonl");
        PrivateMessageLogShipper shipper = shipper(1000, spool);
        shipper.start();
        shipper.submit(pm("last words"));

        shipper.close();

        assertEquals(List.of("last words"), api.sentTexts());
        assertFalse(Files.exists(spool));
    }
}

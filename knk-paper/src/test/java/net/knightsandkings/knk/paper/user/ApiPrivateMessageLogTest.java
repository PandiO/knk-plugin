package net.knightsandkings.knk.paper.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.messaging.PrivateMessageLogEntry;
import net.knightsandkings.knk.core.messaging.PrivateMessageLogShipper;
import net.knightsandkings.knk.core.ports.api.PrivateMessageLogApi;

/**
 * KNG-18 Phase 3: the API sink turns each logged PM into a server-side log entry with its own
 * client message id (batching, overflow, retry and the spool are PrivateMessageLogShipperTest's),
 * and {@link PrivateMessageLogger#all} feeds every sink.
 */
class ApiPrivateMessageLogTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    private static PrivateMessageLogger.Entry entry(PrivateMessageLogger.Outcome outcome, String text) {
        return new PrivateMessageLogger.Entry(Instant.parse("2026-09-26T19:04:05Z"), outcome, "CONSOLE", null,
                "Alice", ALICE, text, true);
    }

    @Test
    void mapsEveryField_AndEachOutcome() {
        UUID id = UUID.randomUUID();
        PrivateMessageLogEntry mapped = ApiPrivateMessageLog.toLogEntry(id, entry(PrivateMessageLogger.Outcome.BLOCKED_FROZEN, "hi"));

        assertEquals(new PrivateMessageLogEntry(id, Instant.parse("2026-09-26T19:04:05Z"), null, "CONSOLE", ALICE, "Alice",
                "hi", PrivateMessageLogEntry.Outcome.BLOCKED_FROZEN, true), mapped);
        for (PrivateMessageLogger.Outcome outcome : PrivateMessageLogger.Outcome.values()) {
            assertEquals(outcome.name(), ApiPrivateMessageLog.toLogEntry(id, entry(outcome, "x")).outcome().name());
        }
    }

    @Test
    void eachMessage_GetsItsOwnId_AndIsSentOnClose() {
        List<PrivateMessageLogEntry> sent = new CopyOnWriteArrayList<>();
        PrivateMessageLogApi api = entries -> {
            sent.addAll(entries);
            return CompletableFuture.completedFuture(new PrivateMessageLogApi.BatchResult(entries.size(), 0));
        };
        ApiPrivateMessageLog log = new ApiPrivateMessageLog(new PrivateMessageLogShipper(api, null, 1000, 50,
                Duration.ofHours(1), Clock.systemUTC()));
        log.start();

        log.log(entry(PrivateMessageLogger.Outcome.DELIVERED, "one"));
        log.log(entry(PrivateMessageLogger.Outcome.DELIVERED, "one"));
        assertEquals(2, log.queueDepth());
        log.close();

        assertEquals(2, sent.size());
        assertNotEquals(sent.get(0).clientMessageId(), sent.get(1).clientMessageId());
    }

    @Test
    void all_FeedsEverySink_EvenWhenOneThrows() {
        List<String> first = new ArrayList<>();
        List<String> second = new ArrayList<>();
        PrivateMessageLogger broken = e -> { throw new IllegalStateException("disk full"); };
        PrivateMessageLogger both = PrivateMessageLogger.all(List.of(e -> first.add(e.text()), broken, e -> second.add(e.text())));

        both.log(entry(PrivateMessageLogger.Outcome.DELIVERED, "hi"));

        assertEquals(List.of("hi"), first);
        assertEquals(List.of("hi"), second);
        assertSame(PrivateMessageLogger.NONE, PrivateMessageLogger.all(List.of()));
    }
}

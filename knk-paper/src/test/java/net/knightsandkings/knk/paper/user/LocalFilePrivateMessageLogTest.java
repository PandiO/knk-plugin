package net.knightsandkings.knk.paper.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.knightsandkings.knk.paper.user.PrivateMessageLogger.Entry;
import net.knightsandkings.knk.paper.user.PrivateMessageLogger.Outcome;

/** KNG-18 Phase 1: plugin-local PM log - daily files, line format, retention sweep. */
class LocalFilePrivateMessageLogTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Amsterdam");
    private static final Instant NOW = Instant.parse("2026-09-26T19:04:05.678Z"); // 21:04:05 local
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @TempDir
    Path dir;

    private LocalFilePrivateMessageLog log;

    private LocalFilePrivateMessageLog newLog(int retentionDays) {
        log = new LocalFilePrivateMessageLog(dir, retentionDays, Clock.fixed(NOW, ZONE));
        return log;
    }

    @AfterEach
    void close() {
        if (log != null) {
            log.close();
        }
    }

    @Test
    void formatLine_matchesTheDesign() {
        Entry entry = new Entry(NOW, Outcome.DELIVERED, "Alice", ALICE, "Bob", BOB, "hi\nthere", false);

        assertEquals("2026-09-26T21:04:05+02:00 | DELIVERED | Alice(" + ALICE + ") -> Bob(" + BOB + ") | hi there",
                LocalFilePrivateMessageLog.formatLine(entry, ZONE));
    }

    @Test
    void formatLine_console() {
        Entry entry = new Entry(NOW, Outcome.BLOCKED_FROZEN, "CONSOLE", null, "Bob", BOB, "hi", true);

        assertEquals("2026-09-26T21:04:05+02:00 | BLOCKED_FROZEN | CONSOLE -> Bob(" + BOB + ") | hi",
                LocalFilePrivateMessageLog.formatLine(entry, ZONE));
    }

    @Test
    void log_appendsToTodaysFile_inOrder() throws Exception {
        newLog(30);
        log.log(new Entry(NOW, Outcome.DELIVERED, "Alice", ALICE, "Bob", BOB, "one", false));
        log.log(new Entry(NOW, Outcome.DELIVERED, "Bob", BOB, "Alice", ALICE, "two", true));
        log.flush();

        List<String> lines = Files.readAllLines(dir.resolve("private-messages-2026-09-26.log"), StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).endsWith("| one"));
        assertTrue(lines.get(1).endsWith("| two"));
    }

    @Test
    void sweep_deletesFilesPastRetention_only() throws Exception {
        newLog(30);
        LocalDate today = LocalDate.of(2026, 9, 26);
        Path old = Files.writeString(dir.resolve("private-messages-" + today.minusDays(31) + ".log"), "x");
        Path edge = Files.writeString(dir.resolve("private-messages-" + today.minusDays(30) + ".log"), "x");
        Path recent = Files.writeString(dir.resolve("private-messages-" + today + ".log"), "x");
        Path unrelated = Files.writeString(dir.resolve("latest.log"), "x");
        Path badDate = Files.writeString(dir.resolve("private-messages-notadate.log"), "x");

        assertEquals(1, log.sweep());

        assertFalse(Files.exists(old));
        assertTrue(Files.exists(edge));
        assertTrue(Files.exists(recent));
        assertTrue(Files.exists(unrelated));
        assertTrue(Files.exists(badDate));
    }

    @Test
    void sweep_missingDirectory_isFine() throws Exception {
        log = new LocalFilePrivateMessageLog(dir.resolve("missing"), 30, Clock.fixed(NOW, ZONE));

        assertEquals(0, log.sweep());
    }

    @Test
    void logAfterClose_isDroppedSilently() {
        newLog(30);
        log.close();

        log.log(new Entry(NOW, Outcome.DELIVERED, "Alice", ALICE, "Bob", BOB, "late", false));
    }
}
